package de.fiereu.openmmo.client.net

import de.fiereu.network.PipelineOptions
import de.fiereu.network.SessionIdentity
import de.fiereu.network.SessionPhase
import de.fiereu.network.Side
import de.fiereu.network.TypedProtocolHandler
import de.fiereu.network.installNetwork
import de.fiereu.openmmo.common.enums.Language
import de.fiereu.openmmo.common.enums.LoginState
import de.fiereu.openmmo.net.login.LoginProtocol
import de.fiereu.openmmo.net.login.packets.GameServerData
import de.fiereu.openmmo.net.login.packets.GameServerListPacket
import de.fiereu.openmmo.net.login.packets.GameServerNodesPacket
import de.fiereu.openmmo.net.login.packets.JoinGameServerPacket
import de.fiereu.openmmo.net.login.packets.LoginKickPacket
import de.fiereu.openmmo.net.login.packets.LoginRequestPacket
import de.fiereu.openmmo.net.login.packets.LoginResponsePacket
import de.fiereu.openmmo.net.login.packets.PasswordLogin
import de.fiereu.openmmo.net.login.packets.RequestGameServerListPacket
import de.fiereu.openmmo.net.login.packets.SentCredentialsPacket
import de.fiereu.openmmo.net.login.packets.TokenLogin
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.bootstrap.Bootstrap
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.socket.nio.NioSocketChannel
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/** What the login server hands over for the game server connection. */
data class GameTicket(
    val host: String,
    val port: Int,
    val data: GameServerData,
    /**
     * Remember-me token (base64) the login server handed out with this login: on a password login
     * with `remember = true`, and on every token login (sliding expiry). Null when none was sent.
     */
    val rememberToken: String? = null,
)

class LoginException(message: String) : RuntimeException(message)

/**
 * Logs in against the login server and asks for a game server ticket.
 *
 * Flow: handshake -> LoginRequest -> LoginResponse(AUTHED) -> RequestGameServerList ->
 * GameServerList -> JoinGameServer -> GameServerNodes(ticket).
 */
class LoginClient(
    private val group: EventLoopGroup,
    private val rootPublic: ECPublicKey,
) {
  fun login(
      host: String,
      port: Int,
      username: String,
      password: String,
      timeoutSeconds: Long = 15,
  ): GameTicket = login(host, port, username, password, null, false, timeoutSeconds)

  /**
   * Logs in with a remember-me [token] (base64, from an earlier [GameTicket.rememberToken]) when it
   * is set, else with [password]. [remember] asks the server for a token on a password login.
   */
  fun login(
      host: String,
      port: Int,
      username: String,
      password: String?,
      token: String?,
      remember: Boolean,
      timeoutSeconds: Long = 15,
  ): GameTicket {
    val result = CompletableFuture<GameTicket>()
    val handler = Handler(username, password, token, remember, host, result)
    val channel =
        Bootstrap()
            .group(group)
            .channel(NioSocketChannel::class.java)
            .option(ChannelOption.TCP_NODELAY, true)
            .installNetwork(
                identity = SessionIdentity.ClientTrust(rootPublic),
                applicationProtocol = LoginProtocol,
                applicationHandlerFactory = { handler },
                options = PipelineOptions(),
            )
            .connect(host, port)
            .sync()
            .channel()
    channel.closeFuture().addListener {
      result.completeExceptionally(LoginException("login connection closed before a ticket"))
    }
    try {
      return result.get(timeoutSeconds, TimeUnit.SECONDS)
    } finally {
      channel.close()
    }
  }

  private class Handler(
      private val username: String,
      private val password: String?,
      private val token: String?,
      private val remember: Boolean,
      private val host: String,
      private val result: CompletableFuture<GameTicket>,
  ) : TypedProtocolHandler<LoginProtocol>(LoginProtocol, Side.CLIENT) {

    override fun handlerAdded(ctx: ChannelHandlerContext) {
      super.handlerAdded(ctx)
      // This handler replaces the handshake handler, so channelActive never fires for it.
      // The handshake marks the session ESTABLISHED right after the replace.
      session.onPhase(SessionPhase.ESTABLISHED) { sendLogin() }
    }

    private fun sendLogin() {
      log.info { "Logging in as $username" }
      session.send(
          LoginRequestPacket(
              username = username,
              manualLogin = true,
              hwid = ByteArray(16),
              method =
                  if (token != null) TokenLogin(java.util.Base64.getDecoder().decode(token))
                  // The retail client sends the SHA-1 hex digest, and that is what the server
                  // stores.
                  else PasswordLogin(sha1Hex(password ?: ""), stayLoggedIn = remember),
              language = Language.EN,
              clientRevision = 0,
              installationRevision = 0,
              os = 1u,
              hardwareInfoCache = ByteArray(0),
          ))
    }

    /** Remember-me token from SentCredentials, if the server sent one. */
    @Volatile private var issued: String? = null

    init {
      on<LoginResponsePacket> { event ->
        val state = event.packet.state
        if (state != LoginState.AUTHED) {
          result.completeExceptionally(LoginException("login refused: $state"))
          return@on
        }
        log.info { "Authenticated, requesting server list" }
        event.session.send(RequestGameServerListPacket())
      }
      on<SentCredentialsPacket> { event -> issued = event.packet.key.takeIf { it.isNotEmpty() } }
      on<GameServerListPacket> { event ->
        val server =
            event.packet.gameServers.firstOrNull { it.joinable }
                ?: run {
                  result.completeExceptionally(LoginException("no joinable game server"))
                  return@on
                }
        log.info { "Joining game server '${server.name}' (id=${server.id})" }
        event.session.send(JoinGameServerPacket(server.id))
      }
      on<GameServerNodesPacket> { event ->
        val data = event.packet.gameServerData
        val node = event.packet.nodes.firstOrNull()
        if (event.packet.loginState != LoginState.AUTHED || data == null || node == null) {
          result.completeExceptionally(
              LoginException("game server refused: ${event.packet.loginState}"))
          return@on
        }
        // A loopback node is only meaningful relative to the host we reached the login server
        // on, so keep that host and take the port from the ticket.
        result.complete(GameTicket(host, node.port.toInt(), data, issued))
      }
      on<LoginKickPacket> { event ->
        result.completeExceptionally(LoginException("kicked by login server: ${event.packet}"))
      }
    }
  }
}

internal fun sha1Hex(text: String): String =
    MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") {
      "%02x".format(it)
    }
