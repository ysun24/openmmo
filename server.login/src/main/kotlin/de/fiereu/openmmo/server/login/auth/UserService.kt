package de.fiereu.openmmo.server.login.auth

import de.fiereu.openmmo.common.enums.LoginState
import de.fiereu.openmmo.common.utils.toHex
import de.fiereu.openmmo.net.login.packets.RegisterResult
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

interface UserService {
  data class AuthResult(val state: LoginState, val userId: Int? = null, val tokenEpoch: Int = 0)

  /** Identity a remember me token resolves to, with the epoch that token has to still match. */
  data class TokenUser(
      val id: Int,
      val username: String,
      val displayName: String,
      val tokenEpoch: Int,
  )

  suspend fun authenticate(username: String, password: String): AuthResult

  suspend fun getUserId(username: String): Int?

  suspend fun findForToken(userId: Int): TokenUser?

  suspend fun hasAnyUser(): Boolean

  /** Takes the plain password and hashes it the way the client would before sending. */
  suspend fun addUser(username: String, password: String): Int

  /**
   * Creates [username] with the client's password digest when [invite] is still valid, using up one
   * of its uses in the same transaction. Inputs are checked with [RegistrationRules] first.
   */
  suspend fun register(username: String, clientDigest: String, invite: String): RegisterResult =
      RegisterResult.SYSTEM_ERROR
}

/** What a registration has to look like, shared by the server and anything validating early. */
object RegistrationRules {
  private val USERNAME = Regex("^[A-Za-z0-9_]{3,16}$")
  private val DIGEST = Regex("^[0-9a-f]{40}$")
  private val INVITE = Regex("^[A-Z0-9-]{4,32}$")

  fun validUsername(name: String) = USERNAME.matches(name)

  /** The client sends the SHA-1 hex; the 8 character minimum is checked before hashing. */
  fun validDigest(digest: String) = DIGEST.matches(digest)

  fun normalizeInvite(code: String) = code.trim().uppercase()

  fun validInvite(code: String) = INVITE.matches(code)

  /** First failing rule, or null when the request may go to the database. */
  fun check(username: String, clientDigest: String, invite: String): RegisterResult? =
      when {
        !validUsername(username) -> RegisterResult.BAD_USERNAME
        !validDigest(clientDigest) -> RegisterResult.BAD_PASSWORD
        !validInvite(normalizeInvite(invite)) -> RegisterResult.BAD_INVITE
        else -> null
      }
}

@Suppress("kotlin:S4790")
internal fun sha1Hex(value: String): String =
    MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).toHex()

@Singleton
class InMemoryUserStore @Inject constructor() : UserService {

  private data class UserInfo(
      val id: Int,
      val passwordHash: String,
      val username: String,
      val tokenEpoch: Int = 0,
  )

  private val users = ConcurrentHashMap<String, UserInfo>()
  private val nextId = AtomicInteger(1)

  /** Invite code to remaining uses, for tests. */
  val invites = ConcurrentHashMap<String, Int>()

  init {
    put("admin", "admin")
    put("test", "test")
  }

  override suspend fun hasAnyUser(): Boolean = users.isNotEmpty()

  override suspend fun addUser(username: String, password: String): Int = put(username, password)

  private fun put(username: String, password: String): Int {
    val id = nextId.getAndIncrement()
    users[username.lowercase()] = UserInfo(id, sha1Hex(password), username)
    return id
  }

  override suspend fun authenticate(username: String, password: String): UserService.AuthResult {
    val user =
        users[username.lowercase()] ?: return UserService.AuthResult(LoginState.INVALID_PASSWORD)
    if (!PasswordHasher.constantTimeEquals(user.passwordHash, password)) {
      return UserService.AuthResult(LoginState.INVALID_PASSWORD)
    }
    return UserService.AuthResult(LoginState.AUTHED, user.id, user.tokenEpoch)
  }

  override suspend fun getUserId(username: String): Int? = users[username.lowercase()]?.id

  override suspend fun register(
      username: String,
      clientDigest: String,
      invite: String,
  ): RegisterResult = synchronized(this) { registerLocked(username, clientDigest, invite) }

  private fun registerLocked(
      username: String,
      clientDigest: String,
      invite: String
  ): RegisterResult {
    RegistrationRules.check(username, clientDigest, invite)?.let {
      return it
    }
    val code = RegistrationRules.normalizeInvite(invite)
    val left = invites[code] ?: 0
    if (left <= 0) return RegisterResult.BAD_INVITE
    if (users.containsKey(username.lowercase())) return RegisterResult.USERNAME_TAKEN
    invites[code] = left - 1
    users[username.lowercase()] = UserInfo(nextId.getAndIncrement(), clientDigest, username)
    return RegisterResult.OK
  }

  override suspend fun findForToken(userId: Int): UserService.TokenUser? =
      users.values
          .firstOrNull { it.id == userId }
          ?.let {
            UserService.TokenUser(it.id, it.username.lowercase(), it.username, it.tokenEpoch)
          }
}
