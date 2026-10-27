package de.fiereu.openmmo.server.game.storage

import de.fiereu.openmmo.common.CharacterInfo
import de.fiereu.openmmo.common.test.DockerAvailable
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import org.flywaydb.core.Flyway
import org.jooq.impl.DSL
import org.testcontainers.containers.PostgreSQLContainer

/** Friends, blocks, guilds and the trade log against a real Postgres, with the V8_4 migration. */
@EnabledIf(DockerAvailable::class)
class SocialRepositoryIT :
    FunSpec({
      val container = PostgreSQLContainer<Nothing>("postgres:18")
      val entityIds = EntityIdService()
      lateinit var characters: JooqCharacterRepository
      lateinit var repo: JooqSocialRepository
      var nextUser = 5000

      fun now(): LocalDateTime = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS)

      suspend fun character(name: String): Long {
        val id = entityIds.newCharacterId()
        characters.insertAggregate(
            StoredCharacter(
                CharacterInfo(
                    id = id,
                    name = name,
                    namePrefix = "",
                    userId = nextUser++,
                    rivalSex = 0,
                    lastLogin = now(),
                    createdAt = now(),
                    money = 0,
                    permissions = 8,
                    remainingSafariSteps = 0,
                    remainingSafariBalls = 0,
                    pcExtraSlots = 0,
                    battleBoxExtraSlots = 0,
                    templateAmount = 0,
                    positionRegionId = 1,
                    positionBankId = 51,
                    positionMapId = 3,
                    positionX = 4,
                    positionY = 4,
                    repelLeft = 0,
                    repelItemId = 0,
                    lureLeft = 0,
                    lureItemId = 0,
                ),
                mutableListOf(),
                mutableListOf(),
                mutableMapOf()))
        return id
      }

      beforeSpec {
        container.start()
        Flyway.configure()
            .dataSource(container.jdbcUrl, container.username, container.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        val d = DSL.using(container.jdbcUrl, container.username, container.password)
        characters = JooqCharacterRepository(d, Dispatchers.IO)
        repo = JooqSocialRepository(d, Dispatchers.IO)
      }

      afterSpec { container.stop() }

      test("names resolve case-insensitively") {
        val id = character("Brock")
        repo.findByName("bROCK").shouldNotBeNull().id shouldBe id
        repo.findById(id).shouldNotBeNull().name shouldBe "Brock"
        repo.findByName("Nobody").shouldBeNull()
      }

      test("friends are one-way with a mutual flag; blocks are separate") {
        val ash = character("Ash")
        val gary = character("Gary")
        repo.addFriend(ash, gary, now()) shouldBe true
        repo.addFriend(ash, gary, now()) shouldBe false
        repo.friends(ash).single().mutual shouldBe false
        repo.friendedBy(gary) shouldContainExactly listOf(ash)
        repo.addFriend(gary, ash, now())
        repo.friends(ash).single().let {
          it.friend.name shouldBe "Gary"
          it.mutual shouldBe true
        }
        repo.removeFriend(ash, gary) shouldBe true
        repo.friends(ash) shouldBe emptyList()
        repo.block(ash, gary, now()) shouldBe true
        repo.blocked(ash).map { it.name } shouldContainExactly listOf("Gary")
        repo.unblock(ash, gary) shouldBe true
        repo.blocked(ash) shouldBe emptyList()
      }

      test("guilds: unique names, one guild per character, ranks and leadership") {
        val a = character("Lead")
        val b = character("Second")
        val g = repo.createGuild("Rocket", "RKT", a, 5, now()).shouldNotBeNull()
        repo.createGuild("ROCKET", "R2", b, 5, now()).shouldBeNull()
        repo.createGuild("Other", "OT", a, 5, now()).shouldBeNull()
        repo.addMember(g.id, b, 0, now()) shouldBe true
        repo.addMember(g.id, b, 0, now()) shouldBe false
        repo.guildMembers(g.id).map { it.member.name to it.rank } shouldContainExactly
            listOf("Lead" to 5, "Second" to 0)
        repo.transferLeadership(g.id, a, b, 5, 4) shouldBe true
        repo.guildMembers(g.id).map { it.member.name to it.rank } shouldContainExactly
            listOf("Second" to 5, "Lead" to 4)
        repo.setMotd(g.id, "hello")
        repo.guildOf(b).shouldNotBeNull().motd shouldBe "hello"
        repo.addGuildLog(g.id, "first", now())
        repo.addGuildLog(g.id, "second", now())
        repo.guildLog(g.id, 10).map { it.text } shouldContainExactly listOf("second", "first")
        repo.removeMember(g.id, a) shouldBe true
        repo.guildOf(a).shouldBeNull()
        repo.disband(g.id)
        repo.guildOf(b).shouldBeNull()
        repo.guildLog(g.id, 10) shouldBe emptyList()
      }

      test("the trade log reads from either side") {
        val a = character("Trader1")
        val b = character("Trader2")
        repo.recordTrade(a, b, "PIKACHU Lv5", "500 money", now())
        repo.tradeHistory(a, 5).single().let {
          it.partner shouldBe "Trader2"
          it.gave shouldBe "PIKACHU Lv5"
          it.got shouldBe "500 money"
        }
        repo.tradeHistory(b, 5).single().let {
          it.partner shouldBe "Trader1"
          it.gave shouldBe "500 money"
        }
        repo.tradeCount(a) shouldBe 1
      }
    })
