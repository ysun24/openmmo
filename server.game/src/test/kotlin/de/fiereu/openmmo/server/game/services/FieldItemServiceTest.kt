package de.fiereu.openmmo.server.game.services

import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.PokemonMove
import de.fiereu.openmmo.common.enums.CharacterGender
import de.fiereu.openmmo.common.enums.EVs
import de.fiereu.openmmo.common.enums.IVs
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.items.ItemDef
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.moves.MoveRegistry
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import de.fiereu.openmmo.server.game.battle.StatCalculator
import de.fiereu.openmmo.server.game.storage.CharacterStore
import de.fiereu.openmmo.server.game.storage.EntityIdService
import de.fiereu.openmmo.server.game.testsupport.FakeCharacterRepository
import de.fiereu.openmmo.server.game.testsupport.battleService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.LocalDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

internal fun partyMon(ownerId: Long, slot: Int, hp: Int, dexId: Int = 4, level: Int = 10) =
    Pokemon(
        id = EntityIdService().newMonsterId(),
        ownerId = ownerId,
        container = PokemonContainer.PARTY,
        containerSlot = slot.toShort(),
        dexId = dexId,
        seed = 0,
        ot = "Red",
        nickname = "",
        level = level.toByte(),
        hp = hp.toShort(),
        xp = 0,
        eVs = EVs(),
        iVs = IVs(),
        moves = listOf(PokemonMove(10, 3), PokemonMove(45, 40)),
        isShiny = false,
        hasHiddenAbility = false,
        isAlpha = false,
        isSecret = false,
        isFatefulEncounter = false,
        isRaidEncounter = false,
        caughtAt = LocalDateTime.now(),
    )

@OptIn(ExperimentalCoroutinesApi::class)
class FieldItemServiceTest :
    FunSpec({
      val items = ItemRegistry()
      val species = SpeciesRegistry()

      class Fx(val store: CharacterStore, val service: FieldItemService, val charId: Long)

      suspend fun TestScope.fx(hp: Int, bag: Map<ItemDef, Int>): Pair<Fx, Pokemon> {
        val store = CharacterStore(FakeCharacterRepository(), EntityIdService(), backgroundScope)
        val charId = store.createCharacter(1, "Red", CharacterGender.MALE, Region.KANTO).info.id
        val mon = partyMon(charId, 0, hp)
        store.addPokemon(charId, mon)
        bag.forEach { (item, qty) -> store.addItem(charId, items.idOf(item), qty) }
        val service = FieldItemService(store, items, species, MoveRegistry(), battleService(store))
        return Fx(store, service, charId) to mon
      }

      fun Fx.hp() = store.getCharacter(charId)!!.pokemon.single().hp.toInt()

      fun Fx.held(item: ItemDef) = store.getCharacter(charId)!!.items[items.idOf(item)] ?: 0

      fun maxHp(mon: Pokemon) = StatCalculator.computeAll(species.get(mon.dexId)!!, mon).hp

      test("a Potion heals 20 HP, capped at the max, and is used up") {
        runTest {
          val (f, mon) = fx(hp = 5, bag = mapOf(Items.POTION to 2))
          val out = f.service.use(f.charId, items.idOf(Items.POTION), mon.id)
          out.changed shouldBe true
          f.hp() shouldBe minOf(25, maxHp(mon))
          f.held(Items.POTION) shouldBe 1
          out.text shouldContain "restored"
        }
      }

      test("a Potion on a monster at full HP does nothing and costs nothing") {
        runTest {
          val (f, mon) = fx(hp = 0, bag = mapOf(Items.POTION to 1))
          val full = maxHp(mon)
          f.store.updatePokemon(f.charId, mon.copy(hp = full.toShort()))
          val out = f.service.use(f.charId, items.idOf(Items.POTION), mon.id)
          out.changed shouldBe false
          out.text shouldBe "It won't have any effect."
          f.held(Items.POTION) shouldBe 1
        }
      }

      test("a fainted monster takes a Revive but not a Potion") {
        runTest {
          val (f, mon) = fx(hp = 0, bag = mapOf(Items.POTION to 1, Items.REVIVE to 1))
          f.service.use(f.charId, items.idOf(Items.POTION), mon.id).changed shouldBe false
          f.service.use(f.charId, items.idOf(Items.REVIVE), mon.id).changed shouldBe true
          f.hp() shouldBe maxHp(mon) / 2
          f.held(Items.REVIVE) shouldBe 0
        }
      }

      test("an Elixir refills PP up to each move's max") {
        runTest {
          val (f, mon) = fx(hp = 5, bag = mapOf(Items.ELIXIR to 1))
          f.service.use(f.charId, items.idOf(Items.ELIXIR), mon.id).changed shouldBe true
          f.store.getCharacter(f.charId)!!.pokemon.single().moves.map { it.pp.toInt() } shouldBe
              listOf(13, 40)
        }
      }

      test("a status heal finds nothing to cure out of battle") {
        runTest {
          val (f, mon) = fx(hp = 5, bag = mapOf(Items.ANTIDOTE to 1))
          f.service.use(f.charId, items.idOf(Items.ANTIDOTE), mon.id).changed shouldBe false
          f.held(Items.ANTIDOTE) shouldBe 1
        }
      }

      test("a Repel starts the step counter, and a second one waits for it to wear off") {
        runTest {
          val (f, _) = fx(hp = 5, bag = mapOf(Items.REPEL to 2))
          f.service.use(f.charId, items.idOf(Items.REPEL), 0).changed shouldBe true
          f.store.getCharacter(f.charId)!!.info.repelLeft.toInt() shouldBe 100
          f.service.use(f.charId, items.idOf(Items.REPEL), 0).changed shouldBe false
          f.held(Items.REPEL) shouldBe 1
        }
      }

      test("an item the bag does not hold, or one with no field use, is refused") {
        runTest {
          val (f, mon) = fx(hp = 5, bag = mapOf(Items.POKE_BALL to 1))
          f.service.use(f.charId, items.idOf(Items.POTION), mon.id).changed shouldBe false
          f.service.use(f.charId, items.idOf(Items.POKE_BALL), mon.id).text shouldContain
              "can't be used"
        }
      }
    })
