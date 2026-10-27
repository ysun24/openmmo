package de.fiereu.openmmo.server.login.auth

import de.fiereu.openmmo.common.enums.LoginState
import de.fiereu.openmmo.db.login.tables.references.INVITES
import de.fiereu.openmmo.db.login.tables.references.USERS
import de.fiereu.openmmo.net.login.packets.RegisterResult
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jooq.DSLContext
import org.jooq.exception.IntegrityConstraintViolationException

private val log = KotlinLogging.logger {}

@Singleton
class JooqUserStore
@Inject
constructor(
    private val dsl: DSLContext,
    @param:Named("db") private val dispatcher: CoroutineDispatcher,
) : UserService {

  /** Not injected so tests and the IT can pick a cheaper iteration count. */
  internal var hasher: PasswordHasher = PasswordHasher()

  override suspend fun authenticate(username: String, password: String): UserService.AuthResult =
      withContext(dispatcher) {
        val user = dsl.selectFrom(USERS).where(USERS.USERNAME.eq(username.lowercase())).fetchOne()
        if (user == null) {
          hasher.dummyVerify(password)
          return@withContext UserService.AuthResult(LoginState.INVALID_PASSWORD)
        }
        val ok =
            when (user.passwordScheme) {
              PasswordHasher.SCHEME_PBKDF2 -> hasher.verify(password, user.passwordHash)
              PasswordHasher.SCHEME_SHA1 ->
                  PasswordHasher.constantTimeEquals(user.passwordHash, password)
              else -> false
            }
        if (!ok) return@withContext UserService.AuthResult(LoginState.INVALID_PASSWORD)
        if (user.passwordScheme != PasswordHasher.SCHEME_PBKDF2 ||
            hasher.needsRehash(user.passwordHash)) {
          // Transparent migration: only a correct digest gets here, so hash that.
          dsl.update(USERS)
              .set(USERS.PASSWORD_HASH, hasher.hash(password))
              .set(USERS.PASSWORD_SCHEME, PasswordHasher.SCHEME_PBKDF2)
              .where(USERS.ID.eq(user.id))
              .execute()
          log.info { "Upgraded the password hash of user ${user.id} to pbkdf2" }
        }
        UserService.AuthResult(LoginState.AUTHED, user.id, user.tokenEpoch ?: 0)
      }

  override suspend fun findForToken(userId: Int): UserService.TokenUser? =
      withContext(dispatcher) {
        dsl.selectFrom(USERS).where(USERS.ID.eq(userId)).fetchOne()?.let {
          UserService.TokenUser(it.id!!, it.username, it.displayName, it.tokenEpoch ?: 0)
        }
      }

  override suspend fun getUserId(username: String): Int? =
      withContext(dispatcher) {
        dsl.select(USERS.ID)
            .from(USERS)
            .where(USERS.USERNAME.eq(username.lowercase()))
            .fetchOne(USERS.ID)
      }

  override suspend fun hasAnyUser(): Boolean =
      withContext(dispatcher) { dsl.fetchExists(dsl.selectFrom(USERS)) }

  override suspend fun addUser(username: String, password: String): Int =
      withContext(dispatcher) { insertUser(dsl, username, sha1Hex(password)) }

  private fun insertUser(ctx: DSLContext, username: String, clientDigest: String): Int =
      ctx.insertInto(USERS)
          .set(USERS.USERNAME, username.lowercase())
          .set(USERS.DISPLAY_NAME, username)
          .set(USERS.PASSWORD_HASH, hasher.hash(clientDigest))
          .set(USERS.PASSWORD_SCHEME, PasswordHasher.SCHEME_PBKDF2)
          .returning(USERS.ID)
          .fetchSingle()
          .id!!

  override suspend fun register(
      username: String,
      clientDigest: String,
      invite: String,
  ): RegisterResult {
    RegistrationRules.check(username, clientDigest, invite)?.let {
      return it
    }
    val code = RegistrationRules.normalizeInvite(invite)
    return withContext(dispatcher) {
      try {
        dsl.transactionResult { cfg ->
          val tx = cfg.dsl()
          // Row lock, so two registrations cannot both take the last use of a code.
          val row = tx.selectFrom(INVITES).where(INVITES.CODE.eq(code)).forUpdate().fetchOne()
          val now = LocalDateTime.now(ZoneOffset.UTC)
          if (row == null ||
              (row.uses ?: 0) >= (row.maxUses ?: 1) ||
              (row.expiresAt != null && !row.expiresAt!!.isAfter(now))) {
            return@transactionResult RegisterResult.BAD_INVITE
          }
          if (tx.fetchExists(tx.selectFrom(USERS).where(USERS.USERNAME.eq(username.lowercase())))) {
            return@transactionResult RegisterResult.USERNAME_TAKEN
          }
          val id = insertUser(tx, username, clientDigest)
          tx.update(INVITES)
              .set(INVITES.USES, INVITES.USES.plus(1))
              .where(INVITES.CODE.eq(code))
              .execute()
          log.info { "Registered '$username' as user $id with invite ${code.take(4)}…" }
          RegisterResult.OK
        }
      } catch (e: IntegrityConstraintViolationException) {
        // Lost a race on the unique username to a concurrent registration.
        RegisterResult.USERNAME_TAKEN
      }
    }
  }

  /** Admin side of invites: the `invite` CLI subcommand. */
  data class Invite(
      val code: String,
      val createdBy: String,
      val maxUses: Int,
      val uses: Int,
      val expiresAt: LocalDateTime?,
      val createdAt: LocalDateTime?,
  )

  fun createInvite(code: String, createdBy: String, maxUses: Int, expiresAt: LocalDateTime?) {
    dsl.insertInto(INVITES)
        .set(INVITES.CODE, code)
        .set(INVITES.CREATED_BY, createdBy)
        .set(INVITES.MAX_USES, maxUses)
        .set(INVITES.EXPIRES_AT, expiresAt)
        .execute()
  }

  /** Invites that can still be used, newest first. */
  fun activeInvites(now: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)): List<Invite> =
      dsl.selectFrom(INVITES)
          .where(INVITES.USES.lt(INVITES.MAX_USES))
          .and(INVITES.EXPIRES_AT.isNull.or(INVITES.EXPIRES_AT.gt(now)))
          .orderBy(INVITES.CREATED_AT.desc())
          .fetch()
          .map {
            Invite(it.code, it.createdBy, it.maxUses ?: 1, it.uses ?: 0, it.expiresAt, it.createdAt)
          }

  fun revokeInvite(code: String): Boolean =
      dsl.update(INVITES)
          .set(INVITES.USES, INVITES.MAX_USES)
          .where(INVITES.CODE.eq(RegistrationRules.normalizeInvite(code)))
          .execute() > 0
}
