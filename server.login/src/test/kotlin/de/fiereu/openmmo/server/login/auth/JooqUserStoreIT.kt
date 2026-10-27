package de.fiereu.openmmo.server.login.auth

import de.fiereu.openmmo.common.enums.LoginState
import de.fiereu.openmmo.common.test.DockerAvailable
import de.fiereu.openmmo.net.login.packets.RegisterResult
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import org.flywaydb.core.Flyway
import org.jooq.impl.DSL
import org.testcontainers.containers.PostgreSQLContainer

@EnabledIf(DockerAvailable::class)
class JooqUserStoreIT :
    FunSpec({
      val container = PostgreSQLContainer<Nothing>("postgres:18")
      lateinit var store: JooqUserStore
      lateinit var rawDsl: org.jooq.DSLContext

      beforeSpec {
        container.start()
        Flyway.configure()
            .dataSource(container.jdbcUrl, container.username, container.password)
            .locations("classpath:db/migration", "classpath:db/dev")
            .load()
            .migrate()
        val dsl = DSL.using(container.jdbcUrl, container.username, container.password)
        store = JooqUserStore(dsl, Dispatchers.IO).also { it.hasher = PasswordHasher(1_000) }
        rawDsl = dsl
      }

      afterSpec { container.stop() }

      test("the seed migration provides admin and test") {
        store.getUserId("admin") shouldBe 1
        store.getUserId("test") shouldBe 2
      }

      test("authenticate succeeds with the hashed password") {
        val result = store.authenticate("admin", sha1Hex("admin"))
        result.state shouldBe LoginState.AUTHED
        result.userId shouldBe 1
      }

      test("authenticate is case-insensitive on username") {
        store.authenticate("ADMIN", sha1Hex("admin")).state shouldBe LoginState.AUTHED
      }

      test("authenticate fails for a wrong password") {
        store.authenticate("admin", sha1Hex("nope")).state shouldBe LoginState.INVALID_PASSWORD
      }

      test("authenticate fails for an unknown user") {
        store.authenticate("nobody", sha1Hex("pw")).state shouldBe LoginState.INVALID_PASSWORD
      }

      test("addUser returns the generated id and getUserId finds it") {
        val id = store.addUser("Alice", "pw")
        store.getUserId("alice") shouldBe id
      }

      test("a legacy sha1 row still logs in and is migrated to pbkdf2 on that login") {
        val users = de.fiereu.openmmo.db.login.tables.references.USERS
        fun row() = rawDsl.selectFrom(users).where(users.USERNAME.eq("test")).fetchSingle()
        row().passwordScheme shouldBe PasswordHasher.SCHEME_SHA1
        store.authenticate("test", sha1Hex("test")).state shouldBe LoginState.AUTHED
        row().passwordScheme shouldBe PasswordHasher.SCHEME_PBKDF2
        (row().passwordHash == sha1Hex("test")) shouldBe false
        store.authenticate("test", sha1Hex("test")).state shouldBe LoginState.AUTHED
        store.authenticate("test", sha1Hex("wrong")).state shouldBe LoginState.INVALID_PASSWORD
      }

      test("a wrong password does not migrate a legacy row") {
        rawDsl.execute(
            "insert into users(username, display_name, password_hash) values ('legacy','legacy','${sha1Hex("pw")}')")
        store.authenticate("legacy", sha1Hex("nope")).state shouldBe LoginState.INVALID_PASSWORD
        rawDsl.fetchValue("select password_scheme from users where username='legacy'") shouldBe
            "sha1"
      }

      test("register consumes invite uses, refuses expired codes and taken names") {
        store.createInvite("IT-TWO", "admin", 2, null)
        store.createInvite(
            "IT-OLD",
            "admin",
            5,
            java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(1))
        val pw = sha1Hex("password1")
        store.register("Gary", pw, "it-two") shouldBe RegisterResult.OK
        store.register("gary", pw, "IT-TWO") shouldBe RegisterResult.USERNAME_TAKEN
        store.register("misty", pw, "IT-TWO") shouldBe RegisterResult.OK
        store.register("brock", pw, "IT-TWO") shouldBe RegisterResult.BAD_INVITE
        store.register("brock", pw, "IT-OLD") shouldBe RegisterResult.BAD_INVITE
        store.register("brock", pw, "IT-MISSING") shouldBe RegisterResult.BAD_INVITE
        store.authenticate("GARY", pw).state shouldBe LoginState.AUTHED
        store.activeInvites().map { it.code }.contains("IT-TWO") shouldBe false
        store.createInvite("IT-REVOKE", "admin", 3, null)
        store.revokeInvite("it-revoke") shouldBe true
        store.register("brock", pw, "IT-REVOKE") shouldBe RegisterResult.BAD_INVITE
      }
    })
