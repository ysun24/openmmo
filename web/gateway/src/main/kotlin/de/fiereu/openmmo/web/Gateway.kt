package de.fiereu.openmmo.web

import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.EventLoopGroup
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.ReferenceCountUtil
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.security.interfaces.ECPublicKey
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

data class GatewayConfig(
    val host: String = "127.0.0.1",
    val port: Int = 8080,
    val staticRoot: File = File("web/static"),
    val loginHost: String = "127.0.0.1",
    val loginPort: Int = 2106,
    val decomp: File = File("decomp"),
    /** Extra origins allowed besides the gateway's own host, e.g. a reverse proxy's. */
    val allowedOrigins: Set<String> = emptySet(),
    val idleSeconds: Int = 30,
    val maxFrameBytes: Int = 64 * 1024,
)

/** The shared parts every socket uses. */
class Gateway(val config: GatewayConfig, val rootPublic: ECPublicKey) : AutoCloseable {
  /** One loop group for every login and game connection, whatever the number of sockets. */
  val gameGroup: EventLoopGroup =
      MultiThreadIoEventLoopGroup(
          Runtime.getRuntime().availableProcessors().coerceIn(1, 4), NioIoHandler.newFactory())

  /** Blocking client calls (login handshake, connect) run here, never on a netty loop. */
  val blocking: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

  val stateJson = StateJson(CodegenNames(config.decomp))
  val files = StaticFiles(config.staticRoot.toPath())

  private val boss = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
  private val workers = MultiThreadIoEventLoopGroup(0, NioIoHandler.newFactory())
  private var server: Channel? = null

  fun start(): InetSocketAddress {
    val ch =
        ServerBootstrap()
            .group(boss, workers)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(
                object : ChannelInitializer<SocketChannel>() {
                  override fun initChannel(ch: SocketChannel) {
                    ch.pipeline()
                        .addLast(
                            IdleStateHandler(config.idleSeconds.toLong(), 0L, 0L, TimeUnit.SECONDS))
                        .addLast(HttpServerCodec(4096, 8192, 8192))
                        .addLast(HttpObjectAggregator(config.maxFrameBytes))
                        .addLast(OriginGuard(config.allowedOrigins))
                        .addLast(
                            WebSocketServerProtocolHandler(
                                WebSocketServerProtocolConfig.newBuilder()
                                    .websocketPath(WS_PATH)
                                    .checkStartsWith(false)
                                    .maxFramePayloadLength(config.maxFrameBytes)
                                    .allowExtensions(false)
                                    .handshakeTimeoutMillis(10_000)
                                    .build()))
                        .addLast(WebSocketFrameAggregator(config.maxFrameBytes))
                        .addLast(WsSession(this@Gateway))
                        .addLast(StaticFileHandler(files))
                        .addLast(IdleCloser())
                  }
                })
            .bind(config.host, config.port)
            .sync()
            .channel()
    server = ch
    return ch.localAddress() as InetSocketAddress
  }

  fun closeFuture() = server?.closeFuture()

  override fun close() {
    server?.close()?.syncUninterruptibly()
    boss.shutdownGracefully(0, 1, TimeUnit.SECONDS)
    workers.shutdownGracefully(0, 1, TimeUnit.SECONDS)
    gameGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS)
    blocking.shutdownNow()
  }

  companion object {
    const val WS_PATH = "/ws"
  }
}

/**
 * Refuses a WebSocket upgrade whose `Origin` is neither the gateway's own `Host` nor an allowed
 * origin, so another site cannot drive a player's session from their browser.
 */
class OriginGuard(private val allowed: Set<String>) : ChannelInboundHandlerAdapter() {
  override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
    if (msg is FullHttpRequest && msg.uri().substringBefore('?') == Gateway.WS_PATH) {
      val origin = msg.headers().get(HttpHeaderNames.ORIGIN)
      val host = msg.headers().get(HttpHeaderNames.HOST)
      if (!originAllowed(origin, host, allowed)) {
        ReferenceCountUtil.release(msg)
        log.warn { "refused ws upgrade from origin '$origin' (host '$host')" }
        val body = "403 Forbidden: origin not allowed\n".toByteArray()
        val res =
            DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN, Unpooled.wrappedBuffer(body))
        HttpUtil.setContentLength(res, body.size.toLong())
        ctx.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE)
        return
      }
    }
    ctx.fireChannelRead(msg)
  }

  companion object {
    /** No Origin (a non-browser client) is allowed; a browser's must match the Host it used. */
    fun originAllowed(origin: String?, host: String?, allowed: Set<String>): Boolean {
      if (origin == null) return true
      if (origin in allowed) return true
      val authority =
          runCatching { URI(origin) }
              .getOrNull()
              ?.takeIf { it.scheme == "http" || it.scheme == "https" }
              ?.rawAuthority ?: return false
      return host != null && authority.equals(host, ignoreCase = true)
    }
  }
}

/** Closes plain HTTP connections that sit idle; WebSocket idleness is handled by [WsSession]. */
private class IdleCloser : ChannelInboundHandlerAdapter() {
  override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
    if (evt is io.netty.handler.timeout.IdleStateEvent) ctx.close()
    else super.userEventTriggered(ctx, evt)
  }
}
