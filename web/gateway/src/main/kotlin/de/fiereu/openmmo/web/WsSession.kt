package de.fiereu.openmmo.web

import de.fiereu.openmmo.client.net.GameClient
import de.fiereu.openmmo.client.net.LoginClient
import de.fiereu.openmmo.client.net.LoginException
import de.fiereu.openmmo.client.net.NewCharacter
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus
import io.netty.handler.codec.http.websocketx.WebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
import io.netty.handler.timeout.IdleStateEvent
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private val log = KotlinLogging.logger {}

/** Runs tasks one after another on a shared executor, so one socket's messages stay in order. */
class SerialExecutor(private val backing: Executor) : Executor {
  private val queue = ArrayDeque<Runnable>()
  private var running = false

  override fun execute(task: Runnable) {
    synchronized(queue) {
      queue.addLast(task)
      if (running) return
      running = true
    }
    backing.execute(::drain)
  }

  private fun drain() {
    while (true) {
      val next =
          synchronized(queue) {
            queue.pollFirst()
                ?: run {
                  running = false
                  null
                }
          } ?: return
      try {
        next.run()
      } catch (e: Throwable) {
        log.warn(e) { "session task failed" }
      }
    }
  }
}

/** Per-socket login attempts: at most [max] in any [windowMs]. */
class RateLimiter(private val max: Int, private val windowMs: Long) {
  private val times = ArrayDeque<Long>()

  @Synchronized
  fun tryAcquire(nowMs: Long): Boolean {
    while (times.isNotEmpty() && nowMs - times.first() >= windowMs) times.removeFirst()
    if (times.size >= max) return false
    times.addLast(nowMs)
    return true
  }
}

/**
 * One browser socket: logs in with its own [GameClient] and mirrors its state as JSON (PROTOCOL.md
 * sections 2 and 3). Frames arrive on the netty loop; game calls run on [serial]; state is built
 * and sent on the socket's event loop.
 */
class WsSession(private val shared: Gateway) : SimpleChannelInboundHandler<WebSocketFrame>() {
  private lateinit var channel: Channel
  private val serial = SerialExecutor(shared.blocking)
  private val loginLimiter = RateLimiter(LOGIN_ATTEMPTS, 60_000)
  private val loginBusy = AtomicBoolean(false)
  private val closed = AtomicBoolean(false)
  @Volatile private var upgraded = false

  @Volatile private var game: GameSession? = null

  // Owned by the event loop.
  private val differ = SectionDiffer()
  private val movement = MovementTracker()
  private var stateSeq = 0
  private var lastSentNanos = 0L
  private var ticker: ScheduledFuture<*>? = null
  private val dirty = AtomicBoolean(false)
  private val flushScheduled = AtomicBoolean(false)

  override fun handlerAdded(ctx: ChannelHandlerContext) {
    channel = ctx.channel()
  }

  override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
    when (evt) {
      is WebSocketServerProtocolHandler.HandshakeComplete -> {
        upgraded = true
        log.info { "ws open ${channel.remoteAddress()}" }
      }
      is IdleStateEvent -> {
        if (!upgraded) return ctx.fireUserEventTriggered(evt).let {}
        log.info { "ws idle, closing ${ctx.channel().remoteAddress()}" }
        ctx.writeAndFlush(CloseWebSocketFrame(WebSocketCloseStatus.NORMAL_CLOSURE, "idle"))
        ctx.close()
      }
      else -> super.userEventTriggered(ctx, evt)
    }
  }

  override fun channelRead0(ctx: ChannelHandlerContext, frame: WebSocketFrame) {
    if (frame !is TextWebSocketFrame) {
      sendError("only text frames are supported")
      return
    }
    val msg =
        runCatching { Json.parseToJsonElement(frame.text()) as? JsonObject }.getOrNull()
            ?: return sendError("malformed message")
    val t = (msg["t"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (t == null) return sendError("missing field: t")
    when (t) {
      "ping" -> {
        val n = (msg["n"] as? JsonPrimitive)?.let { it.longOrNull ?: it.intOrNull?.toLong() }
        send(
            buildJsonObject {
              put("t", "pong")
              put("n", n)
            })
      }
      "login" -> login(msg)
      else -> {
        val g = game ?: return sendError("not logged in")
        serial.execute {
          val error = Dispatcher.dispatch(t, msg, g)
          if (error != null) sendError(error)
        }
      }
    }
  }

  // --- login ------------------------------------------------------------------------------------

  private fun login(msg: JsonObject) {
    if (game != null) return sendError("already logged in")
    if (!loginLimiter.tryAcquire(System.currentTimeMillis())) {
      return sendLoginFailure("RATE_LIMITED")
    }
    fun field(k: String) = (msg[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
    val user = field("user")
    val pass = (msg["pass"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    // r3-present: remember-me resume. `token` (base64, from an earlier login reply) replaces the
    // password; `remember: true` asks for one on a password login.
    val token =
        (msg["token"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
    val remember = (msg["remember"] as? JsonPrimitive)?.booleanOrNull ?: false
    val name = field("name")?.takeIf { it.isNotEmpty() } ?: user
    val region = (msg["region"] as? JsonPrimitive)?.intOrNull ?: 0
    if (user.isNullOrEmpty() || user.length > 32) return sendLoginFailure("BAD_CREDENTIALS_FORMAT")
    if (token != null) {
      if (token.length > 512 || runCatching { java.util.Base64.getDecoder().decode(token) }.isFailure)
          return sendLoginFailure("BAD_CREDENTIALS_FORMAT")
    } else if (pass.isNullOrEmpty() || pass.length > 128) {
      return sendLoginFailure("BAD_CREDENTIALS_FORMAT")
    }
    if (name == null || name.length > 16 || region !in 0..1) {
      return sendLoginFailure("BAD_CHARACTER")
    }
    if (!loginBusy.compareAndSet(false, true)) return sendError("login already in progress")
    shared.blocking.execute {
      try {
        doLogin(user, pass, token, remember, name, region)
      } finally {
        loginBusy.set(false)
      }
    }
  }

  private fun doLogin(
      user: String,
      pass: String?,
      token: String?,
      remember: Boolean,
      name: String,
      region: Int,
  ) {
    val cfg = shared.config
    val ticket =
        try {
          LoginClient(shared.gameGroup, shared.rootPublic)
              .login(cfg.loginHost, cfg.loginPort, user, pass, token, remember)
        } catch (e: LoginException) {
          log.info { "login for '$user' refused: ${e.message}" }
          return sendLoginFailure(loginErrorCode(e.message))
        } catch (e: java.util.concurrent.ExecutionException) {
          val cause = e.cause
          log.info { "login for '$user' failed: ${cause?.message}" }
          return sendLoginFailure(
              if (cause is LoginException) loginErrorCode(cause.message) else "LOGIN_FAILED")
        } catch (e: java.util.concurrent.TimeoutException) {
          return sendLoginFailure("LOGIN_TIMEOUT")
        } catch (e: Exception) {
          log.warn { "login server unreachable: ${e::class.simpleName}: ${e.message}" }
          return sendLoginFailure("LOGIN_SERVER_UNREACHABLE")
        }
    if (closed.get()) return
    val client =
        GameClient(
            shared.gameGroup,
            shared.rootPublic,
            NewCharacter(name, startingRegion = region.toByte()),
            onChange = ::requestFlush,
        )
    client.overworld.dataRoot = cfg.decomp
    try {
      client.connect(ticket)
    } catch (e: Exception) {
      log.warn { "game server unreachable: ${e.message}" }
      client.close()
      return sendLoginFailure("GAME_SERVER_UNREACHABLE")
    }
    val deadline = System.currentTimeMillis() + ENTER_TIMEOUT_MS
    while (!client.world.snapshot().inWorld && System.currentTimeMillis() < deadline) {
      if (closed.get() || client.closeFuture()?.isDone == true) break
      Thread.sleep(25)
    }
    val snap = client.world.snapshot()
    if (closed.get() || !snap.inWorld) {
      client.close()
      if (!closed.get()) sendLoginFailure("ENTER_WORLD_TIMEOUT")
      return
    }
    client.closeFuture()?.addListener {
      if (!closed.get()) {
        sendError("game connection closed")
        channel.writeAndFlush(
            CloseWebSocketFrame(WebSocketCloseStatus.NORMAL_CLOSURE, "game closed"))
        channel.close()
      }
    }
    val session = GameSession(client)
    // Ask for the market inventory up front so the first state has it.
    client.market.requestInventory()
    channel.eventLoop().execute {
      // shutdown() also runs on this loop, so a socket that closed meanwhile is seen here.
      if (closed.get()) {
        client.close()
        return@execute
      }
      game = session
      send(
          buildJsonObject {
            put("t", "login")
            put("ok", true)
            put("selfId", snap.selfId?.toString())
            put("name", snap.characterName)
            ticket.rememberToken?.let { put("token", it) }
          })
      differ.reset()
      flush()
      ticker =
          channel.eventLoop().scheduleAtFixedRate(::tick, FRAME_MS, FRAME_MS, TimeUnit.MILLISECONDS)
    }
    log.info { "'$user' is in the world as '${snap.characterName}'" }
  }

  private fun sendLoginFailure(error: String) =
      send(
          buildJsonObject {
            put("t", "login")
            put("ok", false)
            put("error", error)
          })

  // --- state push -------------------------------------------------------------------------------

  /** Called by the game client on any change, from any thread. */
  private fun requestFlush() {
    dirty.set(true)
    if (!::channel.isInitialized || game == null) return
    if (!flushScheduled.compareAndSet(false, true)) return
    val waitNanos = (lastSentNanos + FRAME_NANOS - System.nanoTime()).coerceAtLeast(0)
    channel
        .eventLoop()
        .schedule(
            {
              flushScheduled.set(false)
              flush()
            },
            waitNanos,
            TimeUnit.NANOSECONDS)
  }

  private fun tick() {
    if (dirty.get() || movement.anyMoving(System.currentTimeMillis())) requestFlush()
  }

  /** On the event loop. Builds every section and sends the ones that changed. */
  private fun flush() {
    val g = game ?: return
    if (!channel.isActive) return
    val now = System.nanoTime()
    if (now - lastSentNanos < FRAME_NANOS) {
      requestFlush()
      return
    }
    if (!channel.isWritable) {
      // The browser is not keeping up; try again next frame instead of queueing more.
      dirty.set(true)
      return
    }
    dirty.set(false)
    val world = g.client.world.snapshot()
    val moving = movement.update(world.entities.values, System.currentTimeMillis())
    val sections = shared.stateJson.build(g.sources(moving).copy(world = world))
    val changed = differ.diff(sections)
    if (changed.isEmpty()) return
    lastSentNanos = now
    stateSeq++
    send(stateMessage(stateSeq, changed))
  }

  // --- io ---------------------------------------------------------------------------------------

  private fun send(obj: JsonElement) {
    if (!::channel.isInitialized || !channel.isActive) return
    channel.writeAndFlush(TextWebSocketFrame(obj.toString()))
  }

  private fun sendError(msg: String) =
      send(
          buildJsonObject {
            put("t", "error")
            put("msg", msg)
          })

  override fun channelInactive(ctx: ChannelHandlerContext) {
    shutdown()
    super.channelInactive(ctx)
  }

  private fun shutdown() {
    if (!closed.compareAndSet(false, true)) return
    ticker?.cancel(false)
    game?.client?.close()
    if (upgraded) log.info { "ws closed ${channel.remoteAddress()}" }
  }

  override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    log.warn { "ws error: ${cause::class.simpleName}: ${cause.message}" }
    ctx.close()
  }

  companion object {
    const val FRAME_MS = 33L
    const val FRAME_NANOS = FRAME_MS * 1_000_000
    const val LOGIN_ATTEMPTS = 5
    const val ENTER_TIMEOUT_MS = 20_000L

    /** "login refused: INVALID_PASSWORD" -> INVALID_PASSWORD; prose -> NO_JOINABLE_GAME_SERVER. */
    fun loginErrorCode(message: String?): String {
      val tail = message?.substringAfterLast(": ")?.trim().orEmpty()
      if (tail.isEmpty()) return "LOGIN_FAILED"
      return tail.uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_').take(48)
    }

    fun stateMessage(seq: Int, sections: Map<String, JsonElement>) = buildJsonObject {
      put("t", "state")
      put("seq", seq)
      sections.forEach { (k, v) -> put(k, v) }
    }
  }
}
