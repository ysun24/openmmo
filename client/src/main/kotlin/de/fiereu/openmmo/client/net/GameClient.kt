package de.fiereu.openmmo.client.net

import de.fiereu.network.PacketEvent
import de.fiereu.network.PipelineOptions
import de.fiereu.network.SessionContext
import de.fiereu.network.SessionIdentity
import de.fiereu.network.SessionPhase
import de.fiereu.network.Side
import de.fiereu.network.TypedProtocolHandler
import de.fiereu.network.installNetwork
import de.fiereu.openmmo.client.world.ChatLine
import de.fiereu.openmmo.client.world.Entity
import de.fiereu.openmmo.client.world.MapKey
import de.fiereu.openmmo.client.world.WorldState
import de.fiereu.openmmo.common.enums.Arch
import de.fiereu.openmmo.common.enums.Bitness
import de.fiereu.openmmo.common.enums.ChatType
import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.common.enums.Language
import de.fiereu.openmmo.common.enums.Platform
import de.fiereu.openmmo.net.game.codecs.SkinSet
import de.fiereu.openmmo.net.game.packets.CharactersListPacket
import de.fiereu.openmmo.net.game.packets.ChatMessagePacket
import de.fiereu.openmmo.net.game.packets.ChatMessageSendPacket
import de.fiereu.openmmo.net.game.packets.CreateCharacterPacket
import de.fiereu.openmmo.net.game.packets.CurrencyBalancePacket
import de.fiereu.openmmo.net.game.packets.EntityLeavePacket
import de.fiereu.openmmo.net.game.packets.EntityMovePacket
import de.fiereu.openmmo.net.game.packets.FaceDirectionPacket
import de.fiereu.openmmo.net.game.packets.GbaEntityMovePacket
import de.fiereu.openmmo.net.game.packets.JoinPacket
import de.fiereu.openmmo.net.game.packets.JoinResponsePacket
import de.fiereu.openmmo.net.game.packets.KeepAlivePacket
import de.fiereu.openmmo.net.game.packets.LoadEntityPacket
import de.fiereu.openmmo.net.game.packets.LoadMapPacket
import de.fiereu.openmmo.net.game.packets.LocalPlayerStatePacket
import de.fiereu.openmmo.net.game.packets.MapData
import de.fiereu.openmmo.net.game.packets.MovementPacket
import de.fiereu.openmmo.net.game.packets.NewAuthData
import de.fiereu.openmmo.net.game.packets.PokemonContainerPacket
import de.fiereu.openmmo.net.game.packets.RequestCharactersPacket
import de.fiereu.openmmo.net.game.packets.RequestPlayerPacket
import de.fiereu.openmmo.net.game.packets.SelectCharacterPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleSidePartyPacket
import de.fiereu.openmmo.net.game.packets.gtl.GtlSearchPagePacket
import de.fiereu.openmmo.net.game.packets.market.MarketInventoryPacket
import de.fiereu.openmmo.net.game.packets.market.MarketResultPacket
import de.fiereu.openmmo.net.game.packets.trade.TradeStatePacket
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.socket.nio.NioSocketChannel
import java.security.interfaces.ECPublicKey

private val log = KotlinLogging.logger {}

/** What a new character is created with when the account has none. */
data class NewCharacter(val name: String, val gender: Byte = 0, val startingRegion: Byte = 0)

/**
 * The game server connection. Owns a [WorldState] the UI reads, and exposes the few actions a
 * player can take. All packet handling runs on the netty event loop.
 */
class GameClient(
    private val group: EventLoopGroup,
    private val rootPublic: ECPublicKey,
    private val newCharacter: NewCharacter,
    private val onChange: () -> Unit = {},
) {
  val world = WorldState()
  val battle =
      BattleClient(
          send = { packet -> session?.send(packet) },
          onChange = { onChange() },
          myName = { world.snapshot().characterName },
          regionId = { world.snapshot().map?.regionId ?: 0 },
      )

  /** Trading, plus the party, bag and money it shows. */
  val trade = TradeClient(::send, onChange)

  /** Global market state and actions (MarketClient.kt). */
  val market = MarketClient({ session?.send(it) }, { onChange() })

  /** Npcs, dialog, shop and scripted movement. */
  val overworld = OverworldClient(world, { session?.send(it) }, onChange)

  @Volatile private var session: SessionContext? = null
  private var channel: Channel? = null

  fun connect(ticket: GameTicket) {
    val handler = Handler(ticket)
    channel =
        Bootstrap()
            .group(group)
            .channel(NioSocketChannel::class.java)
            .option(ChannelOption.TCP_NODELAY, true)
            .installNetwork(
                identity = SessionIdentity.ClientTrust(rootPublic),
                applicationProtocol = ClientGameProtocol,
                applicationHandlerFactory = { handler },
                options = PipelineOptions(),
            )
            .connect(ticket.host, ticket.port)
            .sync()
            .channel()
  }

  fun closeFuture() = channel?.closeFuture()

  fun disconnect() {
    channel?.close()
  }

  /** Disconnects and releases the overworld's timer thread. The client cannot be reused after. */
  fun close() {
    channel?.close()
    overworld.shutdown()
  }

  /**
   * One step. The server takes the tile the step starts on and the direction, checks it against its
   * own collision and answers a blocked step with a position reset.
   */
  fun step(direction: Direction, running: Boolean = false): Boolean {
    val snap = world.snapshot()
    val self = snap.self ?: return false
    if (!snap.inWorld) return false
    val s = session ?: return false
    val ui = snap.overworld
    if (ui != null &&
        (ui.dialog != null ||
            ui.shop != null ||
            ui.transitioning ||
            ui.scriptLocked ||
            ui.inScript)) {
      return false
    }
    s.send(MovementPacket(self.x, self.y, direction, running))
    // Predict the step so the UI moves at once; a reset from the server corrects it.
    val tx = self.x + direction.dx
    val ty = self.y + direction.dy
    val key = snap.map
    val crossing =
        if (key != null && (tx !in 0 until snap.mapWidth || ty !in 0 until snap.mapHeight))
            MapConnections.cross(world.overworld, key, direction, tx, ty)
        else null
    if (crossing != null) {
      // Walking off the edge onto a connected map: the server moves us without a reload.
      val (target, nx, ny) = crossing
      log.info {
        "Crossing into map ${target.key.regionId}:${target.key.bankId}:${target.key.mapId} at ($nx, $ny)"
      }
      world.switchMap(target.key, target.width, target.height)
      world.relocate(self.id, target.key, nx, ny, direction)
    } else {
      world.move(self.id, tx, ty, direction)
    }
    onChange()
    return true
  }

  fun face(direction: Direction) {
    val self = world.snapshot().self ?: return
    session?.send(FaceDirectionPacket(direction))
    world.move(self.id, self.x, self.y, direction)
    onChange()
  }

  fun send(packet: Any) {
    session?.send(packet)
  }

  fun say(text: String) {
    // Slash commands go through the typed-line packet, the only one the server parses commands
    // from.
    if (text.startsWith("/")) {
      session?.send(de.fiereu.openmmo.net.game.packets.ChatMessageSendPacket(0, text, null))
      return
    }
    session?.send(ChatMessagePacket(ChatType.NORMAL, Language.EN, text, ""))
  }

  /** A slash command, e.g. "/testbattle 19 3". The server only parses these from this packet. */
  fun command(text: String) {
    session?.send(ChatMessageSendPacket(0, text, null))
  }

  private inner class Handler(private val ticket: GameTicket) :
      TypedProtocolHandler<ClientGameProtocol>(ClientGameProtocol, Side.CLIENT) {

    override fun handlerAdded(ctx: ChannelHandlerContext) {
      super.handlerAdded(ctx)
      session.onPhase(SessionPhase.ESTABLISHED) {
        this@GameClient.session = session
        sendJoin()
      }
    }

    private fun sendJoin() {
      log.info { "Joining game server as user ${ticket.data.userId}" }
      session.send(
          JoinPacket(
              authData = NewAuthData(ticket.data.userId, ticket.data.sessionToken),
              mac = ByteArray(6),
              clientRevision = 0,
              installationRevision = 0,
              currentChatLanguage = 0,
              chatLanguages = 1,
              matchmakingLanguages = 1,
              romMask = 0,
              roms = emptyList(),
              clientInfo = emptyMap(),
              platform = Platform.LINUX,
              arch = Arch.X86,
              bitness = Bitness._64,
              unk1 = ByteArray(0),
              unk2 = ByteArray(32),
          ))
    }

    init {
      on<JoinResponsePacket> { event ->
        if (!event.packet.canJoin) {
          log.error { "Game server refused the join" }
          event.session.close { "join refused" }
          return@on
        }
        event.session.send(RequestCharactersPacket())
      }

      on<CharactersListPacket> { event -> onCharacters(event) }

      on<LoadMapPacket> { event ->
        val p = event.packet
        MapConnections.remember(world.overworld, p)
        // Connected maps are preloaded after it (a warp preloads them with reloadPlayer=true as
        // well); only the one that also clears the cache is where the player is.
        if (!p.reloadPlayer || !p.deleteCache) return@on
        val data = p.mapData
        val (w, h) = if (data is MapData.GbaMapData) data.width to data.height else 0 to 0
        log.info { "Entering map ${p.regionId}:${p.bankId}:${p.mapId} (${w}x$h)" }
        world.enterMap(MapKey(p.regionId, p.bankId, p.mapId), w, h, clearEntities = p.deleteCache)
        event.session.send(RequestPlayerPacket())
        onChange()
      }

      on<LoadEntityPacket> { event ->
        val p = event.packet
        val selfId = world.snapshot().selfId
        world.upsert(
            Entity(
                id = p.entityId,
                name = p.name,
                map = MapKey(p.regionId, p.bankId, p.mapId),
                x = p.x,
                y = p.y,
                facing = p.facing,
                isPlayer = true,
            ))
        if (p.entityId == selfId) log.info { "Spawned at (${p.x}, ${p.y})" }
        else log.info { "Player '${p.name}' is here at (${p.x}, ${p.y})" }
        onChange()
      }

      on<GbaEntityMovePacket> { event ->
        val p = event.packet
        log.debug { "move ${p.entityId} -> ${p.bankId}:${p.mapId} (${p.x},${p.y}) ${p.direction}" }
        val current = world.snapshot().entities[p.entityId]?.map
        if (current != null && (current.bankId != p.bankId || current.mapId != p.mapId)) {
          world.relocate(
              p.entityId, MapKey(current.regionId, p.bankId, p.mapId), p.x, p.y, p.direction)
          if (p.entityId == world.snapshot().selfId) {
            val key = MapKey(current.regionId, p.bankId, p.mapId)
            world.overworld.knownMap(key)?.let { world.switchMap(key, it.width, it.height) }
          }
        } else if (!(p.entityId == world.snapshot().selfId && overworld.selfBusy())) {
          world.move(p.entityId, p.x, p.y, p.direction)
        }
        onChange()
      }
      on<EntityMovePacket> { event ->
        val p = event.packet
        world.move(p.entityId, p.x, p.y, p.direction)
        onChange()
      }
      on<EntityLeavePacket> { event ->
        world.remove(event.packet.entityId)
        onChange()
      }

      on<ChatMessagePacket> { event ->
        val p = event.packet
        val sender = p.sender?.takeIf { it.isNotEmpty() } ?: "*"
        log.info { "[chat] $sender: ${p.message}" }
        world.addChat(ChatLine(sender, p.message))
        if (p.sender.isNullOrEmpty()) {
          battle.notice(p.message)
          world.overworld.setNotice(p.message)
        }
        onChange()
      }

      on<KeepAlivePacket> { event -> event.session.send(event.packet) }

      on<TradeStatePacket> { event -> trade.onState(event.packet) }
      on<PokemonContainerPacket> { event -> trade.onContainer(event.packet) }
      // Shared by several features: every interested module gets the packet.
      on<BattleSidePartyPacket> { event ->
        trade.onSideParty(event.packet)
        overworld.handle(event)
      }
      on<LocalPlayerStatePacket> { event ->
        trade.onPlayerState(event.packet)
        overworld.handle(event)
      }
      on<MarketInventoryPacket> { event -> market.onInventory(event.packet) }
      on<MarketResultPacket> { event -> market.onResult(event.packet) }
      on<GtlSearchPagePacket> { event -> market.onPage(event.packet) }
      on<CurrencyBalancePacket> { event ->
        market.onBalance(event.packet)
        trade.onBalance(event.packet.primaryBalance)
        world.overworld.setMoney(event.packet.primaryBalance)
      }
    }

    private fun onCharacters(event: PacketEvent<CharactersListPacket>) {
      val chars = event.packet.characters
      if (chars.isEmpty()) {
        log.info { "No characters yet, creating '${newCharacter.name}'" }
        event.session.send(
            CreateCharacterPacket(
                newCharacter.name, newCharacter.gender, newCharacter.startingRegion, SkinSet()))
        return
      }
      val chosen =
          chars.firstOrNull { it.characterInfo.name.equals(newCharacter.name, true) } ?: chars[0]
      val info = chosen.characterInfo
      log.info { "Selecting character '${info.name}' (id=${info.id})" }
      world.selectCharacter(info.id, info.name)
      market.selfId = info.id
      event.session.send(SelectCharacterPacket(info.id, info.id))
    }

    /** Packets this client does not model yet are expected, not errors. */
    override fun onUnhandled(event: PacketEvent<*>) {
      if (battle.handle(event)) return
      if (overworld.handle(event)) return
      log.debug { "ignored ${event.packet::class.simpleName}" }
    }

    /**
     * A codec that cannot read what the server wrote should not cost the connection: frames are
     * independent, so log it and keep going.
     */
    override fun onError(cause: Throwable) {
      log.warn { "packet error: ${cause::class.simpleName}: ${cause.message}" }
    }

    override fun onInactive() {
      log.info { "Disconnected from game server" }
      world.setInWorld(false)
      onChange()
    }
  }
}
