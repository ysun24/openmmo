package de.fiereu.openmmo.server.game.storage

import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.common.test.DockerAvailable
import de.fiereu.openmmo.db.game.tables.references.POKEMON
import de.fiereu.openmmo.server.game.services.trade.SwapResult
import de.fiereu.openmmo.server.game.services.trade.TradeOffer
import de.fiereu.openmmo.server.game.services.trade.TradeSwap
import de.fiereu.openmmo.server.game.services.trade.tradeMon
import de.fiereu.openmmo.server.game.services.trade.trader
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.testcontainers.containers.PostgreSQLContainer

@EnabledIf(DockerAvailable::class)
class TradeRepositoryIT :
    FunSpec({
      val container = PostgreSQLContainer<Nothing>("postgres:18")
      lateinit var repository: JooqCharacterRepository
      lateinit var dsl: DSLContext

      beforeSpec {
        container.start()
        Flyway.configure()
            .dataSource(container.jdbcUrl, container.username, container.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        dsl = DSL.using(container.jdbcUrl, container.username, container.password)
        repository = JooqCharacterRepository(dsl, Dispatchers.IO)
      }

      afterSpec { container.stop() }

      fun owners(): Map<Long, Long> =
          dsl.select(POKEMON.ID, POKEMON.OWNER_ID).from(POKEMON).fetch().associate {
            it.value1()!! to it.value2()!!
          }

      test("a trade moves monster rows between owners in one transaction") {
        val a = trader("Ash", listOf(1, 4, 7))
        val b = trader("Gary", listOf(25, 133, 1, 2, 3, 6))
        a.pcStorage += tradeMon(a.info.id, 0, 150, PokemonContainer.PC)
        repository.insertAggregate(a)
        repository.insertAggregate(b)

        // Ash gives two and gets one back. Gary's party has room for one again, the other goes to
        // the pc.
        val r =
            TradeSwap.apply(
                    a,
                    TradeOffer(listOf(a.pokemon[0].id, a.pokemon[1].id), mapOf(5017 to 4), 100),
                    b,
                    TradeOffer(listOf(b.pokemon[1].id)),
                )
                .shouldBeInstanceOf<SwapResult.Ok>()
        repository.saveTrade(listOf(a to r.a, b to r.b))

        val la = repository.loadById(a.info.id)!!
        val lb = repository.loadById(b.info.id)!!
        la.pokemon.map { it.dexId } shouldContainExactly listOf(7, 133)
        la.pokemon.map { it.containerSlot.toInt() } shouldContainExactly listOf(0, 1)
        la.pcStorage.map { it.dexId } shouldContainExactly listOf(150)
        lb.pokemon.map { it.dexId } shouldContainExactly listOf(25, 1, 2, 3, 6, 1)
        lb.pokemon.map { it.containerSlot.toInt() } shouldContainExactly (0..5).toList()
        lb.pcStorage.map { it.dexId } shouldContainExactly listOf(4)
        la.items[5017] shouldBe 6
        lb.items[5017] shouldBe 14
        la.info.money shouldBe 900
        lb.info.money shouldBe 1100
        // Nothing was deleted and re-created: every row survived with its id.
        val o = owners()
        o[a.pokemon[0].id] shouldBe b.info.id
        o[a.pokemon[1].id] shouldBe b.info.id
        o[b.pokemon[1].id] shouldBe a.info.id
        o.size shouldBe 10
      }

      test("a failing half rolls the other half back") {
        val a = trader("Ash", listOf(1, 4))
        val b = trader("Gary", listOf(25))
        repository.insertAggregate(a)
        repository.insertAggregate(b)
        val r =
            TradeSwap.apply(a, TradeOffer(listOf(a.pokemon[0].id)), b, TradeOffer(money = 10))
                .shouldBeInstanceOf<SwapResult.Ok>()
        // Gary's side claims a slot Ash's received monster now holds: the deferred unique
        // constraint fails at commit, and the whole trade must vanish with it.
        val clash =
            r.b.copy(pokemon = r.b.pokemon.map { it.copy(containerSlot = 0) }.toMutableList())
        runCatching { repository.saveTrade(listOf(a to r.a, b to clash)) }.isFailure shouldBe true

        val la = repository.loadById(a.info.id)!!
        la.pokemon.map { it.dexId } shouldContainExactly listOf(1, 4)
        la.info.money shouldBe 1000
        repository.loadById(b.info.id)!!.pokemon.map { it.dexId } shouldContainExactly listOf(25)
      }
    })
