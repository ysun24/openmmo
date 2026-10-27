package de.fiereu.openmmo.server.game.services

import de.fiereu.network.PacketEvent
import de.fiereu.openmmo.common.enums.CharacterGender
import de.fiereu.openmmo.common.enums.EncounterMethod
import de.fiereu.openmmo.common.enums.MapType
import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.common.enums.TileBehavior
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.maps.MapDef
import de.fiereu.openmmo.maps.WildEncounterSlot
import de.fiereu.openmmo.maps.WildEncounterTable
import de.fiereu.openmmo.moves.MoveRegistry
import de.fiereu.openmmo.net.game.packets.battle.BattleExtPacket
import de.fiereu.openmmo.net.game.packets.battle.EvolutionReplyPacket
import de.fiereu.openmmo.pokemon.EvolutionRegistry
import de.fiereu.openmmo.pokemon.LearnsetRegistry
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import de.fiereu.openmmo.server.game.battle.BattleTerrain
import de.fiereu.openmmo.server.game.battle.MoveLearnPrompts
import de.fiereu.openmmo.server.game.battle.MoveLearner
import de.fiereu.openmmo.server.game.battle.testMon
import de.fiereu.openmmo.server.game.session.SessionRegistry
import de.fiereu.openmmo.server.game.storage.CharacterStore
import de.fiereu.openmmo.server.game.storage.EntityIdService
import de.fiereu.openmmo.server.game.testsupport.FakeCharacterRepository
import de.fiereu.openmmo.server.game.testsupport.FakeSession
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.LocalTime
import kotlin.random.Random
import kotlinx.coroutines.test.runTest

private val items = ItemRegistry()

private fun evolutionService(store: CharacterStore) =
    EvolutionService(
        store,
        SpeciesRegistry(),
        EvolutionRegistry(),
        items,
        MoveLearner(LearnsetRegistry(), MoveRegistry()),
        MoveLearnPrompts(),
        SessionRegistry(),
        EntityIdService())

class EvolutionAndEncounterTest :
    FunSpec({
      val evo = evolutionService(CharacterStore(FakeCharacterRepository(), EntityIdService(), kotlinx.coroutines.GlobalScope))

      test("level evolutions follow the decomp table") {
        evo.tryEvolve(testMon(4, 15, listOf(33)), EvolutionTrigger.LEVEL_UP).shouldBeNull()
        evo.tryEvolve(testMon(4, 16, listOf(33)), EvolutionTrigger.LEVEL_UP) shouldBe 5
        // Treecko 16 -> Grovyle (Hoenn species keep their national numbers).
        evo.tryEvolve(testMon(252, 16, listOf(33)), EvolutionTrigger.LEVEL_UP) shouldBe 253
      }

      test("an Everstone stops a level evolution") {
        val held = items.idOf(Items.EVERSTONE)
        evo.tryEvolve(testMon(4, 20, listOf(33), held = held), EvolutionTrigger.LEVEL_UP).shouldBeNull()
      }

      test("friendship evolutions need 220 and the right time of day") {
        // Golbat -> Crobat at any time.
        evo.tryEvolve(testMon(42, 20, listOf(33), friendship = 219), EvolutionTrigger.LEVEL_UP).shouldBeNull()
        evo.tryEvolve(testMon(42, 20, listOf(33), friendship = 220), EvolutionTrigger.LEVEL_UP) shouldBe 169
        // Eevee: Espeon by day, Umbreon at night.
        evo.clock = { LocalTime.of(14, 0) }
        evo.tryEvolve(testMon(133, 20, listOf(33), friendship = 255), EvolutionTrigger.LEVEL_UP) shouldBe 196
        evo.clock = { LocalTime.of(2, 0) }
        evo.tryEvolve(testMon(133, 20, listOf(33), friendship = 255), EvolutionTrigger.LEVEL_UP) shouldBe 197
      }

      test("stones evolve the right species and nothing else") {
        val eevee = testMon(133, 5, listOf(33))
        evo.tryEvolve(eevee, EvolutionTrigger.ITEM, Items.FIRE_STONE) shouldBe 136
        evo.tryEvolve(eevee, EvolutionTrigger.ITEM, Items.WATER_STONE) shouldBe 134
        evo.tryEvolve(eevee, EvolutionTrigger.ITEM, Items.THUNDERSTONE) shouldBe 135
        evo.tryEvolve(testMon(25, 5, listOf(33)), EvolutionTrigger.ITEM, Items.FIRE_STONE).shouldBeNull()
        evo.tryEvolve(eevee, EvolutionTrigger.LEVEL_UP).shouldBeNull()
      }

      test("trade evolutions, with and without a held item") {
        evo.tryEvolve(testMon(64, 20, listOf(33)), EvolutionTrigger.TRADE) shouldBe 65
        evo.tryEvolve(testMon(95, 20, listOf(33)), EvolutionTrigger.TRADE).shouldBeNull()
        val coat = items.idOf(Items.METAL_COAT)
        evo.tryEvolve(testMon(95, 20, listOf(33), held = coat), EvolutionTrigger.TRADE) shouldBe 208
      }

      test("Tyrogue's evolution compares attack and defense; Wurmple uses the personality") {
        val wurmple = evo.tryEvolve(testMon(265, 7, listOf(33), seed = 0x0000_0000), EvolutionTrigger.LEVEL_UP)
        wurmple shouldBe 266 // Silcoon: (personality >> 16) % 10 = 0
        evo.tryEvolve(testMon(265, 7, listOf(33), seed = 0x0009_0000), EvolutionTrigger.LEVEL_UP) shouldBe 268
      }

      test("an evolution scene asks the client and completes on its answer") {
        runTest {
          val repo = FakeCharacterRepository()
          val store = CharacterStore(repo, EntityIdService(), this)
          val service = evolutionService(store)
          val created = store.createCharacter(1, "Ash", CharacterGender.MALE, Region.HOENN)
          val charmander = testMon(4, 16, listOf(33)).copy(ownerId = created.info.id)
          store.addPokemon(created.info.id, charmander)
          val session = FakeSession(created.info.id)
          service.afterBattle(session, created.info.id, listOf(charmander.id))
          session.sent.filterIsInstance<BattleExtPacket>().single().json.contains("\"phase\":\"start\"").shouldBeTrue()
          service.onReply(PacketEvent(EvolutionReplyPacket(charmander.id, proceed = true), session))
          store.getCharacter(created.info.id)!!.pokemon.single { it.id == charmander.id }.dexId shouldBe 5
        }
      }

      test("pressing B stops the evolution") {
        runTest {
          val store = CharacterStore(FakeCharacterRepository(), EntityIdService(), this)
          val service = evolutionService(store)
          val created = store.createCharacter(1, "Ash", CharacterGender.MALE, Region.HOENN)
          val mon = testMon(4, 16, listOf(33)).copy(ownerId = created.info.id)
          store.addPokemon(created.info.id, mon)
          val session = FakeSession(created.info.id)
          service.startEvolution(session, created.info.id, mon.id, 5)
          service.onReply(PacketEvent(EvolutionReplyPacket(mon.id, proceed = false), session))
          store.getCharacter(created.info.id)!!.pokemon.single { it.id == mon.id }.dexId shouldBe 4
        }
      }

      test("a stone is consumed when its evolution completes") {
        runTest {
          val store = CharacterStore(FakeCharacterRepository(), EntityIdService(), this)
          val service = evolutionService(store)
          val created = store.createCharacter(1, "Ash", CharacterGender.MALE, Region.HOENN)
          val id = created.info.id
          val pikachu = testMon(25, 10, listOf(33)).copy(ownerId = id)
          store.addPokemon(id, pikachu)
          val stone = items.idOf(Items.THUNDERSTONE)
          store.addItem(id, stone, 1)
          val session = FakeSession(id)
          service.useEvolutionItem(session, id, pikachu.id, stone).shouldBeNull()
          service.onReply(PacketEvent(EvolutionReplyPacket(pikachu.id, true), session))
          store.getCharacter(id)!!.pokemon.single { it.id == pikachu.id }.dexId shouldBe 26
          (store.getCharacter(id)!!.items[stone] ?: 0) shouldBe 0
        }
      }

      // --- wild encounters ------------------------------------------------------------------------

      test("cave floors, deep sand and FireRed land tiles roll the land table; water the water table") {
        TileBehavior.CAVE.hasLandEncounters.shouldBeTrue()
        TileBehavior.DEEP_SAND.hasLandEncounters.shouldBeTrue()
        TileBehavior.LAND_ENCOUNTER.hasLandEncounters.shouldBeTrue()
        TileBehavior.DEEP_WATER.isSurfableWater.shouldBeTrue()
        (TileBehavior.NORMAL.hasLandEncounters || TileBehavior.SAND.hasLandEncounters) shouldBe false
      }

      test("battle backgrounds follow BattleSetup_GetEnvironmentId") {
        val route = MapDef(0, 0, 0, mapType = MapType.ROUTE)
        val cave = MapDef(0, 0, 1, mapType = MapType.UNDERGROUND)
        EncounterService.terrainFor(route, TileBehavior.TALL_GRASS) shouldBe BattleTerrain.GRASS
        EncounterService.terrainFor(route, TileBehavior.LONG_GRASS) shouldBe BattleTerrain.LONG_GRASS
        EncounterService.terrainFor(route, TileBehavior.DEEP_SAND) shouldBe BattleTerrain.SAND
        EncounterService.terrainFor(route, TileBehavior.DEEP_WATER) shouldBe BattleTerrain.WATER
        EncounterService.terrainFor(route, TileBehavior.POND_WATER) shouldBe BattleTerrain.POND
        EncounterService.terrainFor(cave, TileBehavior.CAVE) shouldBe BattleTerrain.CAVE
        EncounterService.terrainFor(MapDef(0, 0, 2, mapType = MapType.INSIDE), TileBehavior.NORMAL) shouldBe BattleTerrain.BUILDING
        EncounterService.terrainFor(route, TileBehavior.NORMAL) shouldBe BattleTerrain.PLAIN
      }

      test("slots follow the decomp percentages and levels stay in the slot range") {
        val enc = EncounterService(CharacterStore(FakeCharacterRepository(), EntityIdService(), kotlinx.coroutines.GlobalScope), mockBattleService())
        enc.random = Random(3)
        val weights = listOf(20, 20, 10, 10, 10, 10, 5, 5, 4, 4, 1, 1)
        val table = WildEncounterTable(EncounterMethod.LAND, 20, weights.mapIndexed { i, w -> WildEncounterSlot(1 + i, 3, 5, w) })
        val counts = IntArray(12)
        repeat(20000) { counts[enc.pickSlot(table, null, kanto = true)!!.speciesId - 1]++ }
        counts[0] shouldBeInRange 3600..4400
        counts[11] shouldBeInRange 100..330
        repeat(100) { enc.chooseLevel(table.slots[0], null, kanto = true) shouldBeInRange 3..5 }
      }

      test("the encounter chance is the table rate x16 out of 2880, Stench halves it") {
        val enc = EncounterService(CharacterStore(FakeCharacterRepository(), EntityIdService(), kotlinx.coroutines.GlobalScope), mockBattleService())
        val table = WildEncounterTable(EncounterMethod.LAND, 21, emptyList())
        enc.encounterChance(table, testMon(1, 5, listOf(33)), kanto = false, buff = 0) shouldBe 336
        // Grimer's Stench (dex 88, ability1 STENCH).
        enc.encounterChance(table, testMon(88, 5, listOf(33)), kanto = false, buff = 0) shouldBe 168
        enc.encounterChance(WildEncounterTable(EncounterMethod.LAND, 250, emptyList()), testMon(1, 5, listOf(33)), false, 0) shouldBe ENCOUNTER_ROLL_MAX
      }
    })

private fun mockBattleService(): BattleService {
  val scope = kotlinx.coroutines.GlobalScope
  val store = CharacterStore(FakeCharacterRepository(), EntityIdService(), scope)
  val interest = de.fiereu.openmmo.server.game.world.interest.InterestManager()
  val registry = de.fiereu.openmmo.server.game.battle.BattleRegistry()
  val engine = de.fiereu.openmmo.server.game.battle.TurnEngine(MoveRegistry(), de.fiereu.openmmo.typechart.TypeChart())
  val emitter = de.fiereu.openmmo.server.game.battle.BattlePacketEmitter(interest)
  return BattleService(
      store, registry, engine,
      de.fiereu.openmmo.server.game.battle.WildMonFactory(SpeciesRegistry(), MoveRegistry(), LearnsetRegistry(), EntityIdService()),
      emitter, de.fiereu.openmmo.server.game.battle.BattleRewards(), MoveLearner(LearnsetRegistry(), MoveRegistry()),
      interest, SpeciesRegistry(), MoveRegistry(), de.fiereu.openmmo.trainer.TrainerRegistry(), items,
      PvpBattleService(store, SessionRegistry(), registry, engine, emitter, interest, SpeciesRegistry()))
}

