package de.fiereu.openmmo.server.login.handler

import de.fiereu.network.PacketEvent
import de.fiereu.network.Side
import de.fiereu.network.coroutines.CoroutineProtocolHandler
import de.fiereu.openmmo.common.auth.RememberMeTokenIssuer
import de.fiereu.openmmo.common.auth.RememberMeTokenVerifier
import de.fiereu.openmmo.common.auth.SessionTokenIssuer
import de.fiereu.openmmo.common.enums.LoginState
import de.fiereu.openmmo.net.login.LoginProtocol
import de.fiereu.openmmo.net.login.packets.GameServerData
import de.fiereu.openmmo.net.login.packets.GameServerListPacket
import de.fiereu.openmmo.net.login.packets.GameServerNodesPacket
import de.fiereu.openmmo.net.login.packets.JoinGameServerPacket
import de.fiereu.openmmo.net.login.packets.LoginRequestPacket
import de.fiereu.openmmo.net.login.packets.LoginResponsePacket
import de.fiereu.openmmo.net.login.packets.PasswordLogin
import de.fiereu.openmmo.net.login.packets.RegisterRequestPacket
import de.fiereu.openmmo.net.login.packets.RegisterResponsePacket
import de.fiereu.openmmo.net.login.packets.RegisterResult
import de.fiereu.openmmo.net.login.packets.RequestGameServerListPacket
import de.fiereu.openmmo.net.login.packets.SentCredentialsPacket
import de.fiereu.openmmo.net.login.packets.TokenLogin
import de.fiereu.openmmo.server.login.auth.LoginThrottle
import de.fiereu.openmmo.server.login.auth.UserService
import de.fiereu.openmmo.server.login.catalog.GameServerCatalog
import de.fiereu.openmmo.server.login.session.AUTHED_USER_ID
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.InetSocketAddress
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

private val log = KotlinLogging.logger {}

class LoginAppHandler
@Inject
constructor(
    private val users: UserService,
    private val catalog: GameServerCatalog,
    private val tokenIssuer: SessionTokenIssuer,
    private val rememberMeIssuer: RememberMeTokenIssuer,
    private val rememberMeVerifier: RememberMeTokenVerifier,
    scope: CoroutineScope,
    private val throttle: LoginThrottle,
) : CoroutineProtocolHandler<LoginProtocol>(LoginProtocol, Side.SERVER, scope) {

  internal var clock: () -> Long = System::currentTimeMillis

  /** Per connection: one handler instance serves exactly one socket. */
  private var passwordAttempts = 0
  private var registerAttempts = 0

  init {
    onSuspend<LoginRequestPacket> { event -> onLoginRequest(event) }
    onSuspend<RegisterRequestPacket> { event -> onRegister(event) }
    onSuspend<RequestGameServerListPacket> { event -> onServerListRequest(event) }
    onSuspend<JoinGameServerPacket> { event -> onJoinGameServer(event) }
  }

  internal suspend fun onLoginRequest(event: PacketEvent<LoginRequestPacket>) {
    when (val method = event.packet.method) {
      is PasswordLogin -> onPasswordLogin(event, method)
      is TokenLogin -> onTokenLogin(event, method)
    }
  }

  private suspend fun onPasswordLogin(
      event: PacketEvent<LoginRequestPacket>,
      method: PasswordLogin,
  ) {
    val username = event.packet.username
    val now = clock()
    val address = addressOf(event)
    if (++passwordAttempts > MAX_PASSWORD_ATTEMPTS_PER_SOCKET ||
        !throttle.loginAllowed(username, address, now)) {
      log.warn { "Rate limited login for $username from $address" }
      val until = maxOf(throttle.loginRetryAt(username, address, now), now + 60_000)
      event.session.send(
          LoginResponsePacket(
              LoginState.RATE_LIMITED,
              LocalDateTime.ofEpochSecond(until / 1000, 0, ZoneOffset.UTC)))
      return
    }
    val result = users.authenticate(username, method.password)
    log.info { "Login attempt for $username: ${result.state}" }
    if (result.state != LoginState.AUTHED || result.userId == null) {
      if (result.state == LoginState.INVALID_PASSWORD) throttle.loginFailed(username, now)
      event.session.send(LoginResponsePacket(result.state))
      return
    }
    throttle.loginSucceeded(username)
    event.session.attributes[AUTHED_USER_ID] = result.userId
    if (method.stayLoggedIn) {
      sendRememberMeToken(event, result.userId, result.tokenEpoch, username)
    }
    event.session.send(LoginResponsePacket(result.state))
  }

  private suspend fun onTokenLogin(event: PacketEvent<LoginRequestPacket>, method: TokenLogin) {
    val username = event.packet.username
    val token = rememberMeVerifier.verify(method.token)
    val user = token?.let { users.findForToken(it.userId) }
    if (token == null ||
        user == null ||
        user.tokenEpoch != token.epoch ||
        !user.username.equals(username, ignoreCase = true)) {
      log.warn { "Rejected token login for $username" }
      event.session.send(LoginResponsePacket(LoginState.INVALID_SAVED_CREDENTIALS))
      return
    }
    log.info { "Token login for ${user.displayName}: AUTHED" }
    event.session.attributes[AUTHED_USER_ID] = user.id
    // Sliding expiry, so a player who keeps logging in never has to type a password again.
    sendRememberMeToken(event, user.id, user.tokenEpoch, user.displayName)
    event.session.send(LoginResponsePacket(LoginState.AUTHED))
  }

  internal suspend fun onRegister(event: PacketEvent<RegisterRequestPacket>) {
    val pkt = event.packet
    val address = addressOf(event)
    if (event.session.attributes[AUTHED_USER_ID] != null) {
      event.session.send(RegisterResponsePacket(RegisterResult.ALREADY_LOGGED_IN))
      return
    }
    if (++registerAttempts > MAX_REGISTER_ATTEMPTS_PER_SOCKET ||
        !throttle.registrationAllowed(address, clock())) {
      log.warn { "Rate limited registration from $address" }
      event.session.send(RegisterResponsePacket(RegisterResult.RATE_LIMITED))
      return
    }
    val result =
        try {
          users.register(pkt.username, pkt.password, pkt.invite)
        } catch (e: Exception) {
          log.error(e) { "Registration of '${pkt.username}' failed" }
          RegisterResult.SYSTEM_ERROR
        }
    log.info { "Registration of '${pkt.username}' from $address: $result" }
    event.session.send(RegisterResponsePacket(result))
  }

  private fun addressOf(event: PacketEvent<*>): String =
      (event.session.remoteAddress as? InetSocketAddress)?.address?.hostAddress
          ?: event.session.remoteAddress.toString()

  private fun sendRememberMeToken(
      event: PacketEvent<LoginRequestPacket>,
      userId: Int,
      epoch: Int,
      displayName: String,
  ) {
    val issued = rememberMeIssuer.issue(userId, epoch)
    event.session.send(SentCredentialsPacket(displayName, issued.bytes))
  }

  private fun onServerListRequest(event: PacketEvent<RequestGameServerListPacket>) {
    event.session.send(GameServerListPacket(catalog.list()))
  }

  private fun onJoinGameServer(event: PacketEvent<JoinGameServerPacket>) {
    val entry = catalog.find(event.packet.gameServerId)
    if (entry == null) {
      log.warn { "Unknown game server id ${event.packet.gameServerId}" }
      event.session.send(GameServerNodesPacket(LoginState.NO_GS_AVAILABLE))
      return
    }
    val userId = event.session.attributes[AUTHED_USER_ID]
    if (userId == null) {
      log.warn { "Join game server without a completed login" }
      event.session.send(GameServerNodesPacket(LoginState.INVALID_SAVED_CREDENTIALS))
      return
    }
    val token = tokenIssuer.issue(userId = userId.toLong())
    val data =
        GameServerData(
            gameServerId = entry.server.id,
            userId = userId,
            sessionToken = token.bytes,
            localAddress = entry.localAddress,
            localHostname = entry.localHostname,
            port = entry.node.port,
        )
    event.session.send(
        GameServerNodesPacket(
            loginState = LoginState.AUTHED,
            gameServerData = data,
            nodes = listOf(entry.node),
        ),
    )
  }
}

private const val MAX_PASSWORD_ATTEMPTS_PER_SOCKET = 5
private const val MAX_REGISTER_ATTEMPTS_PER_SOCKET = 3
