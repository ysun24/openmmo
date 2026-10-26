package de.fiereu.openmmo.server.game.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import de.fiereu.openmmo.common.CharacterInfo
import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.PokemonMove
import de.fiereu.openmmo.common.enums.EVs
import de.fiereu.openmmo.common.enums.IVs
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.common.test.DockerAvailable
import de.fiereu.openmmo.db.game.tables.references.CHARACTERS
import de.fiereu.openmmo.db.game.tables.references.POKEMON
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.testcontainers.containers.PostgreSQLContainer

/** The market against a real Postgres: escrow, payment, returns and the buy race. */
@EnabledIf(DockerAvailable::class)
class MarketRepositoryIT :
    FunSpec({
      val container = PostgreSQLContainer<Nothing>("postgres:18")
      val entityIds = EntityIdService()
      lateinit var pool: HikariDataSource
      lateinit var dsl: DSLContext
      lateinit var characters: JooqCharacterRepository
      lateinit var market: MarketRepository
      val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

      fun now(): LocalDateTime = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS)

      fun later(): LocalDateTime = now().plusDays(7)

      fun monster(ownerId: Long, container: PokemonContainer, slot: Int, dex: Int = 25): Pokemon =
          Pokemon(
              id = entityIds.newMonsterId(),
              ownerId = ownerId,
              container = container,
              containerSlot = slot.toShort(),
              dexId = dex,
              seed = 7,
              ot = "Seller",
              nickname = "",
              level = 5,
              hp = 20,
              xp = 100,
              eVs = EVs(),
              iVs = IVs(),
              moves = List(4) { PokemonMove(if (it == 0) 33 else 0, if (it == 0) 35 else 0) },
              isShiny = false,
              hasHiddenAbility = false,
              isAlpha = false,
              isSecret = false,
              isFatefulEncounter = false,
              isRaidEncounter = false,
              caughtAt = now(),
          )

      var nextUser = 1000

      suspend fun character(
          money: Int = 10_000,
          partySize: Int = 2,
          items: Map<Int, Int> = emptyMap()
      ): Long {
        val id = entityIds.newCharacterId()
        val info =
            CharacterInfo(
                id = id,
                name = "C$id",
                namePrefix = "",
                userId = nextUser++,
                rivalSex = 0,
                lastLogin = now(),
                createdAt = now(),
                money = money,
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
            )
        characters.insertAggregate(
            StoredCharacter(
                info,
                MutableList(partySize) { monster(id, PokemonContainer.PARTY, it, dex = 1 + it) },
                mutableListOf(),
                items.toMutableMap(),
            ))
        return id
      }

      fun money(id: Long): Int =
          dsl.select(CHARACTERS.MONEY)
              .from(CHARACTERS)
              .where(CHARACTERS.ID.eq(id))
              .fetchOne()!!
              .value1()!!

      fun ownerAndContainer(monId: Long): Pair<Long, String> =
          dsl.select(POKEMON.OWNER_ID, POKEMON.CONTAINER)
              .from(POKEMON)
              .where(POKEMON.ID.eq(monId))
              .fetchOne()!!
              .let { it.value1()!! to it.value2()!! }

      beforeSpec {
        container.start()
        Flyway.configure()
            .dataSource(container.jdbcUrl, container.username, container.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        // A real pool, so concurrent buys run on separate connections and really contend.
        pool =
            HikariDataSource(
                HikariConfig().apply {
                  jdbcUrl = container.jdbcUrl
                  username = container.username
                  password = container.password
                  maximumPoolSize = 12
                })
        dsl = DSL.using(pool, SQLDialect.POSTGRES)
        characters = JooqCharacterRepository(dsl, Dispatchers.IO)
        market = MarketRepository(dsl, Dispatchers.IO)
      }

      afterSpec {
        storeScope.cancel()
        pool.close()
        container.stop()
      }

      test("listing a pokemon escrows it and hides it from the seller's party") {
        val seller = character(partySize = 3)
        val listed = characters.loadById(seller)!!.pokemon.first { it.containerSlot == 1.toShort() }

        val outcome = market.createPokemonListing(seller, listed.id, 500, now(), later(), 10)

        outcome.shouldBeInstanceOf<MarketOutcome.Done>()
        ownerAndContainer(listed.id) shouldBe (seller to "GTS")
        val party = characters.loadById(seller)!!.pokemon
        party.map { it.id }.contains(listed.id) shouldBe false
        // The remaining party stays packed into slots 0..n-1.
        party.map { it.containerSlot.toInt() }.sorted() shouldBe listOf(0, 1)
      }

      test("the last party pokemon cannot be listed") {
        val seller = character(partySize = 1)
        val only = characters.loadById(seller)!!.pokemon.single()

        val outcome = market.createPokemonListing(seller, only.id, 500, now(), later(), 10)

        outcome shouldBe MarketOutcome.Refused("You cannot list your last party pokemon.")
        ownerAndContainer(only.id) shouldBe (seller to "PARTY")
      }

      test("concurrent buys of one listing: exactly one wins and pays exactly once") {
        val seller = character(money = 1000)
        val mon = characters.loadById(seller)!!.pokemon.first()
        val listing =
            (market.createPokemonListing(seller, mon.id, 700, now(), later(), 10)
                    as MarketOutcome.Done)
                .listing
        val buyers = List(10) { character(money = 5000, partySize = 1) }
        val start = CountDownLatch(1)

        val outcomes = coroutineScope {
          buyers
              .map { buyer ->
                async(Dispatchers.IO) {
                  start.await()
                  market.buy(listing.id, buyer, now())
                }
              }
              .also { start.countDown() }
              .awaitAll()
        }

        val winners = outcomes.withIndex().filter { it.value is MarketOutcome.Done }
        winners shouldHaveSize 1
        val winner = buyers[winners.single().index]
        outcomes.filterIsInstance<MarketOutcome.Refused>().forEach {
          it.reason shouldBe "That listing is no longer for sale."
        }
        money(seller) shouldBe 1700
        money(winner) shouldBe 4300
        buyers.filter { it != winner }.forEach { money(it) shouldBe 5000 }
        ownerAndContainer(mon.id) shouldBe (winner to "PARTY")
        characters.loadById(winner)!!.pokemon.map { it.id }.contains(mon.id) shouldBe true
        market.find(listing.id)!!.status shouldBe MarketStatus.SOLD
        market.find(listing.id)!!.buyerId shouldBe winner
      }

      test("a buyer without enough money, or the seller, cannot buy") {
        val seller = character()
        val mon = characters.loadById(seller)!!.pokemon.first()
        val listing =
            (market.createPokemonListing(seller, mon.id, 9000, now(), later(), 10)
                    as MarketOutcome.Done)
                .listing
        val poor = character(money = 100)

        market.buy(listing.id, poor, now()) shouldBe
            MarketOutcome.Refused("You cannot afford 9000.")
        market.buy(listing.id, seller, now()) shouldBe
            MarketOutcome.Refused("You cannot buy your own listing.")
        money(poor) shouldBe 100
        market.find(listing.id)!!.status shouldBe MarketStatus.ACTIVE
      }

      test("a buyer with a full party gets the pokemon in the pc") {
        val seller = character()
        val mon = characters.loadById(seller)!!.pokemon.first()
        val listing =
            (market.createPokemonListing(seller, mon.id, 10, now(), later(), 10)
                    as MarketOutcome.Done)
                .listing
        val buyer = character(partySize = 6)

        market.buy(listing.id, buyer, now()).shouldBeInstanceOf<MarketOutcome.Done>()

        ownerAndContainer(mon.id) shouldBe (buyer to "PC")
        characters.loadById(buyer)!!.pcStorage.single().id shouldBe mon.id
      }

      test("item listings escrow the stack, a sale pays the seller and a cancel returns it") {
        val seller = character(money = 0, items = mapOf(13 to 10))
        val buyer = character(money = 1000, items = mapOf(13 to 1))

        val sold =
            (market.createItemListing(seller, 13, 4, 250, now(), later(), 10) as MarketOutcome.Done)
                .listing
        val kept =
            (market.createItemListing(seller, 13, 6, 999, now(), later(), 10) as MarketOutcome.Done)
                .listing
        characters.loadById(seller)!!.items[13].shouldBeNull()
        market.createItemListing(seller, 13, 1, 5, now(), later(), 10) shouldBe
            MarketOutcome.Refused("You do not have 1 of item 13.")

        market.buy(sold.id, buyer, now()).shouldBeInstanceOf<MarketOutcome.Done>()
        characters.loadById(buyer)!!.items[13] shouldBe 5
        money(buyer) shouldBe 750
        money(seller) shouldBe 250

        market.cancel(kept.id, buyer, now()) shouldBe
            MarketOutcome.Refused("That is not your listing.")
        market.cancel(kept.id, seller, now()).shouldBeInstanceOf<MarketOutcome.Done>()
        characters.loadById(seller)!!.items[13] shouldBe 6
        market.cancel(kept.id, seller, now()) shouldBe
            MarketOutcome.Refused("That listing is closed.")
      }

      test("an expired listing cannot be bought and expiry returns the pokemon") {
        val seller = character(partySize = 2)
        val mon = characters.loadById(seller)!!.pokemon.first()
        val created = now()
        val listing =
            (market.createPokemonListing(seller, mon.id, 50, created, created.plusSeconds(1), 10)
                    as MarketOutcome.Done)
                .listing
        val buyer = character()
        val afterExpiry = created.plusSeconds(5)

        market.buy(listing.id, buyer, afterExpiry) shouldBe
            MarketOutcome.Refused("That listing has expired.")
        market.expire(listing.id, created) shouldBe
            MarketOutcome.Refused("That listing is still running.")
        market.dueForExpiry(afterExpiry).map { it.first }.contains(listing.id) shouldBe true

        market.expire(listing.id, afterExpiry).shouldBeInstanceOf<MarketOutcome.Done>()

        ownerAndContainer(mon.id) shouldBe (seller to "PARTY")
        characters.loadById(seller)!!.pokemon shouldHaveSize 2
        market.find(listing.id)!!.status shouldBe MarketStatus.EXPIRED
        market.dueForExpiry(afterExpiry).map { it.first }.contains(listing.id) shouldBe false
      }

      test("search filters by kind, ref id and price, sorts and pages") {
        val seller = character(partySize = 6, items = mapOf(77 to 30))
        val party = characters.loadById(seller)!!.pokemon
        // dex ids 1..6; list 2..5 at different prices.
        party
            .filter { it.dexId in 2..5 }
            .forEach { mon ->
              market.createPokemonListing(seller, mon.id, mon.dexId * 100, now(), later(), 50)
            }
        repeat(3) { i -> market.createItemListing(seller, 77, 1, 10 + i, now(), later(), 50) }

        val cheapFirst =
            market.search(
                MarketQuery(
                    kind = MarketKind.POKEMON, sellerId = seller, sort = MarketSort.PRICE_ASC),
                now())
        cheapFirst.total shouldBe 4
        cheapFirst.rows.map { it.listing.price } shouldBe listOf(200, 300, 400, 500)
        cheapFirst.rows.forEach { it.pokemon.shouldNotBeNull() }

        market
            .search(
                MarketQuery(kind = MarketKind.POKEMON, refIds = listOf(3), sellerId = seller),
                now())
            .rows
            .map { it.listing.dexId } shouldBe listOf(3)
        market
            .search(
                MarketQuery(
                    kind = MarketKind.POKEMON,
                    minPrice = 300,
                    maxPrice = 400,
                    sellerId = seller,
                    sort = MarketSort.PRICE_DESC),
                now())
            .rows
            .map { it.listing.price } shouldBe listOf(400, 300)
        val items =
            market.search(
                MarketQuery(
                    kind = MarketKind.ITEM,
                    refIds = listOf(77),
                    sellerId = seller,
                    sort = MarketSort.NEWEST,
                    pageSize = 2,
                    page = 1),
                now())
        items.total shouldBe 3
        items.rows.map { it.listing.price } shouldBe listOf(10)
        market.search(MarketQuery(sellerId = seller), now()).total shouldBe 7
        market
            .search(MarketQuery(kind = MarketKind.ITEM, refIds = listOf(99999)), now())
            .rows
            .shouldBeEmpty()
      }

      test("a sale through the character cache keeps cache and database in step") {
        val store = CharacterStore(characters, entityIds, storeScope)
        val seller = character(money = 100, partySize = 2)
        val buyer = character(money = 5000, partySize = 1)
        // Both online: their aggregates are cached and would be flushed back later.
        val sellerMon = store.getOrLoadCharacter(seller)!!.pokemon.first()
        store.getOrLoadCharacter(buyer)
        store.updatePosition(seller, 9, 9) // an unrelated pending change

        val listing =
            (store.withExternalWrite(listOf(seller)) {
                  market.createPokemonListing(seller, sellerMon.id, 400, now(), later(), 10)
                } as MarketOutcome.Done)
                .listing
        store.getCharacter(seller)!!.pokemon.map { it.id }.contains(sellerMon.id) shouldBe false

        store
            .withExternalWrite(listOf(buyer, seller)) { market.buy(listing.id, buyer, now()) }
            .shouldBeInstanceOf<MarketOutcome.Done>()

        store.getCharacter(seller)!!.info.money shouldBe 500
        store.getCharacter(seller)!!.info.positionX shouldBe 9.toShort()
        store.getCharacter(buyer)!!.info.money shouldBe 4600
        store.getCharacter(buyer)!!.pokemon.map { it.id }.contains(sellerMon.id) shouldBe true

        // A later flush of either cached copy must not write the old balance or delete the mon.
        store.updatePosition(buyer, 3, 3)
        store.updatePosition(seller, 8, 8)
        store.flushAll()
        money(seller) shouldBe 500
        money(buyer) shouldBe 4600
        ownerAndContainer(sellerMon.id) shouldBe (buyer to "PARTY")
      }
    })
