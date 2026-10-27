package de.fiereu.openmmo.server.game.progression

import de.fiereu.openmmo.common.PokemonMove
import de.fiereu.openmmo.common.enums.CharacterGender
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import de.fiereu.openmmo.progression.Gen3Data
import de.fiereu.openmmo.server.game.battle.ExpCurves
import de.fiereu.openmmo.server.game.services.partyMon
import de.fiereu.openmmo.server.game.storage.CharacterStore
import de.fiereu.openmmo.server.game.storage.EntityIdService
import de.fiereu.openmmo.server.game.testsupport.FakeCharacterRepository
import de.fiereu.openmmo.server.game.testsupport.daycareService
import de.fiereu.openmmo.server.game.testsupport.progressService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

private object ZeroRandom : Random() {
  override fun nextBits(bitCount: Int): Int = 0
}

@OptIn(ExperimentalCoroutinesApi::class)
class ProgressionTest :
    FunSpec({
      val species = SpeciesRegistry()

      suspend fun TestScope.store(): Pair<CharacterStore, Long> {
        val store = CharacterStore(FakeCharacterRepository(), EntityIdService(), backgroundScope)
        val id = store.createCharacter(1, "Red", CharacterGender.MALE, Region.HOENN).info.id
        return store to id
      }

      fun mon(owner: Long, slot: Int, dex: Int, level: Int, seed: Int = 0, ot: String = "Red") =
          partyMon(owner, slot, hp = 20, dexId = dex, level = level)
              .copy(
                  seed = seed,
                  ot = ot,
                  xp = ExpCurves.totalXpFor(species.get(dex)!!.growthRate, level))

      // ---------------------------------------------------------------- Gen3 tables
      test("Gen 3 TM/HM table follows tms_hms.h") {
        Gen3Data.tmhmMoves.size shouldBe 58
        Gen3Data.tmhmMove(0) shouldBe 264 // FOCUS PUNCH
        Gen3Data.tmhmMove(38) shouldBe 317 // TM39 ROCK TOMB
        Gen3Data.tmhmMove(50) shouldBe 15 // HM01 CUT
        Gen3Data.tmhmMove(57) shouldBe 291 // HM08 DIVE
        Gen3Data.isHmMove(57) shouldBe true // SURF
        Gen3Data.canLearnTmhm(129, 5) shouldBe false // Magikarp, TOXIC
        Gen3Data.canLearnTmhm(1, 5) shouldBe true
      }

      test("egg species are the bottom of the line, with the incense exceptions") {
        Gen3Data.babyOf(26) shouldBe 172 // Raichu -> Pichu
        Gen3Data.babyOf(3) shouldBe 1
        Gen3Data.babyOf(184) shouldBe 183 // Azumarill -> Marill (no Sea Incense)
        Gen3Data.babyOf(202) shouldBe 202 // Wobbuffet (no Lax Incense)
        Gen3Data.eggMoves(1) shouldContain 113 // LIGHT SCREEN
      }

      test("decoration marts from the decomp stock lists") {
        Gen3Data.decorStocks
            .getValue("FortreeCity_DecorationShop_PokemartDecor_Desks")
            .size shouldBe 8
        Gen3Data.decorations.getValue(1).name shouldBe "SMALL DESK"
        Gen3Data.decorations.getValue(1).price shouldBe 3000
        Gen3Data.decorStocks.keys shouldContain "Route104_PrettyPetalFlowerShop_Pokemart_Plants"
      }

      // ---------------------------------------------------------------- badges
      test("badges are the regions' FLAG_BADGE0n_GET flags; obedience follows 2/4/6/8") {
        runTest {
          val (store, id) = store()
          val badges = BadgeService(store)
          badges.mask(id) shouldBe 0
          badges.obedienceLevel(id, Region.HOENN) shouldBe 10
          store.setStoryFlag(id, "hoenn/FLAG_BADGE01_GET")
          store.setStoryFlag(id, "hoenn/FLAG_BADGE02_GET")
          store.setStoryFlag(id, "kanto/FLAG_BADGE08_GET")
          badges.mask(id) shouldBe ((0b11 shl 8) or (1 shl 7))
          badges.count(id, Region.HOENN) shouldBe 2
          badges.obedienceLevel(id, Region.HOENN) shouldBe 30
          badges.obedienceLevel(id, Region.KANTO) shouldBe 100
        }
      }

      test("HMs need their region's badge outside battle") {
        runTest {
          val (store, id) = store()
          val badges = BadgeService(store)
          badges.canUseFieldMove(id, Region.HOENN, 15) shouldBe false // CUT needs STONE BADGE
          store.setStoryFlag(id, "hoenn/FLAG_BADGE01_GET")
          badges.canUseFieldMove(id, Region.HOENN, 15) shouldBe true
          badges.canUseFieldMove(id, Region.KANTO, 15) shouldBe false // CASCADE in FireRed
          badges.canUseFieldMove(id, Region.KANTO, 148) shouldBe false // FLASH: BOULDER
          badges.canUseFieldMove(id, Region.KANTO, 33) shouldBe true // no HM
        }
      }

      // ---------------------------------------------------------------- pokedex
      test("every monster received is registered caught; a battle registers seen") {
        runTest {
          val (store, id) = store()
          store.addPokemon(id, mon(id, 0, 258, 5))
          store.getCharacter(id)!!.dexCaught shouldContain 258
          store.registerSeen(id, listOf(263))
          store.getCharacter(id)!!.dexSeen shouldContainAll listOf(258, 263)
          store.getCharacter(id)!!.dexCaught shouldNotContain 263
        }
      }

      test("eggs are not registered until they hatch") {
        runTest {
          val (store, id) = store()
          store.addPokemon(id, mon(id, 0, 175, 5).copy(isEgg = true))
          store.getCharacter(id)!!.dexCaught shouldNotContain 175
        }
      }

      // ---------------------------------------------------------------- day care rules
      test("compatibility: same species different trainer 70, same trainer 50, Ditto 20/50") {
        val male = mon(1, 0, 25, 20, seed = 0xFF) // ratio 127: low byte 255 -> male
        val female = mon(1, 1, 25, 20, seed = 0x00)
        val pikachu = species.get(25)!!
        DaycareRules.compatibility(male, pikachu, female, pikachu) shouldBe DaycareRules.MED
        DaycareRules.compatibility(male, pikachu, female.copy(ot = "Gary"), pikachu) shouldBe
            DaycareRules.MAX
        val ditto = mon(1, 1, 132, 20)
        DaycareRules.compatibility(male, pikachu, ditto, species.get(132)!!) shouldBe
            DaycareRules.LOW
        DaycareRules.compatibility(male, pikachu, male, pikachu) shouldBe DaycareRules.INCOMPATIBLE
        // Undiscovered egg group (Pichu) never breeds.
        DaycareRules.compatibility(mon(1, 0, 172, 5), species.get(172)!!, female, pikachu) shouldBe
            DaycareRules.INCOMPATIBLE
        DaycareRules.fee(0) shouldBe 100
        DaycareRules.fee(3) shouldBe 400
      }

      test("depositing keeps one able monster; steps become exp and levels; the fee follows") {
        runTest {
          val (store, id) = store()
          val daycare = daycareService(store)
          val a = mon(id, 0, 1, 10)
          store.addPokemon(id, a)
          daycare.deposit(id, a.id)!! shouldContain "only have one"
          val b = mon(id, 1, 4, 10)
          store.addPokemon(id, b)
          daycare.deposit(id, a.id) shouldBe null
          store.getCharacter(id)!!.daycare.single().container shouldBe PokemonContainer.DAYCARE
          store.getCharacter(id)!!.pokemon.single().containerSlot.toInt() shouldBe 0
          daycare.state(store.getCharacter(id)!!) shouldBe DaycareService.State.ONE
          // Bulbasaur 10 -> 11 needs MEDIUM_SLOW 11 minus 10.
          val need =
              ExpCurves.totalXpFor(species.get(1)!!.growthRate, 11) -
                  ExpCurves.totalXpFor(species.get(1)!!.growthRate, 10)
          repeat(need) { daycare.onStep(id) }
          val left = store.getCharacter(id)!!.daycare.single()
          daycare.levelsGained(store.getCharacter(id)!!, left) shouldBe 1
          store.addMoney(id, -store.getCharacter(id)!!.info.money + 1000)
          val money = store.getCharacter(id)!!.info.money
          val (refused, told) = daycare.withdraw(id, 0)
          refused shouldBe null
          told!! shouldContain "grown by 1"
          store.getCharacter(id)!!.info.money shouldBe money - 200
          val back = store.getCharacter(id)!!.pokemon.first { it.id == a.id }
          back.level.toInt() shouldBe 11
          back.container shouldBe PokemonContainer.PARTY
        }
      }

      test("withdrawing without the fee is refused") {
        runTest {
          val (store, id) = store()
          val daycare = daycareService(store)
          val a = mon(id, 0, 1, 10)
          store.addPokemon(id, a)
          store.addPokemon(id, mon(id, 1, 4, 10))
          daycare.deposit(id, a.id)
          store.addMoney(id, -store.getCharacter(id)!!.info.money + 50)
          daycare.withdraw(id, 0).first shouldBe "You don't have enough money."
        }
      }

      test(
          "a compatible pair lays an egg on the 255th step; it hatches into the baby with egg moves") {
            runTest {
              val (store, id) = store()
              // A roll of 0 always lays the egg; the coin flips pick the mother.
              val daycare = daycareService(store).also { it.random = ZeroRandom }
              // Raichu (female) + Pikachu (male, knows an egg move of Pichu: none -> use a TM
              // move).
              val mother = mon(id, 0, 26, 30, seed = 0x00, ot = "Red")
              val father =
                  mon(id, 1, 25, 30, seed = 0xFF, ot = "Gary")
                      .copy(
                          moves =
                              listOf(
                                  PokemonMove(85, 15),
                                  PokemonMove(0, 0),
                                  PokemonMove(0, 0),
                                  PokemonMove(0, 0)))
              store.addPokemon(id, mother)
              store.addPokemon(id, father)
              store.addPokemon(id, mon(id, 2, 1, 10))
              daycare.deposit(id, mother.id)
              daycare.deposit(id, father.id)
              daycare.compatibility(daycare.main(store.getCharacter(id)!!)) shouldBe
                  DaycareRules.MED
              repeat(255) { daycare.onStep(id) }
              daycare.state(store.getCharacter(id)!!) shouldBe DaycareService.State.EGG_WAITING
              val egg = daycare.createEgg(store.getCharacter(id)!!).shouldNotBeNull()
              egg.dexId shouldBe 172
              egg.isEgg shouldBe true
              // THUNDERBOLT (TM24) from the father, which Pichu can learn by TM.
              egg.moves.map { it.id.toInt() } shouldContain 85
              val hatched = daycare.hatch(egg)
              hatched.isEgg shouldBe false
              hatched.level.toInt() shouldBe 5
            }
          }

      test("FLAME BODY / MAGMA ARMOR in the party takes two egg cycles off every 255 steps") {
        runTest {
          val (store, id) = store()
          val daycare = daycareService(store)
          // Slugma (218): MAGMA ARMOR / FLAME BODY; Bulbasaur: OVERGROW.
          daycare.cyclesToSubtract(listOf(mon(id, 0, 1, 10))) shouldBe 1
          daycare.cyclesToSubtract(listOf(mon(id, 0, 218, 10))) shouldBe 2
          daycare.cyclesToSubtract(listOf(mon(id, 0, 218, 10).copy(isEgg = true))) shouldBe 1
          store.addPokemon(id, mon(id, 0, 218, 10))
          val egg = mon(id, 1, 172, 5).copy(isEgg = true)
          store.addPokemon(id, egg)
          val key = "${ProgressKeys.EGG_STEPS_PREFIX}${egg.id}"
          store.setStoryVar(id, key, 11) // 10 cycles left
          repeat(255) { daycare.onStep(id) }
          store.getCharacter(id)!!.storyVars[key] shouldBe 9
        }
      }

      test("an egg in the party hatches after its species' egg cycles of 255 steps") {
        runTest {
          val (store, id) = store()
          val daycare = daycareService(store)
          store.addPokemon(id, mon(id, 0, 1, 10))
          val egg = mon(id, 1, 172, 5).copy(isEgg = true)
          store.addPokemon(id, egg)
          // One cycle left (the var stores cycles + 1).
          store.setStoryVar(id, "${ProgressKeys.EGG_STEPS_PREFIX}${egg.id}", 2)
          var lines = emptyList<String>()
          repeat(255 * 2) { if (lines.isEmpty()) lines = daycare.onStep(id) }
          lines.single() shouldContain "hatched from the EGG"
          val after = store.getCharacter(id)!!.pokemon.first { it.id == egg.id }
          after.isEgg shouldBe false
          store.getCharacter(id)!!.dexCaught shouldContain 172
        }
      }

      // ---------------------------------------------------------------- decorations
      test("a decoration shop sells its stock for money; the PC keeps the count") {
        runTest {
          val (store, id) = store()
          val progress = progressService(store)
          val decor = DecorationService(store, progress)
          decor.buy(id, 1, 1) shouldBe "No shop is open."
          decor.open(id, "FortreeCity_DecorationShop_PokemartDecor_Desks") shouldBe true
          decor.buy(id, 10, 1) shouldBe "That is not for sale here."
          val money = store.getCharacter(id)!!.info.money
          decor.buy(id, 1, 2) shouldBe null
          store.getCharacter(id)!!.info.money shouldBe money - 6000
          decor.owned(id, 1) shouldBe 2
          store.addMoney(id, -store.getCharacter(id)!!.info.money)
          decor.buy(id, 1, 1) shouldBe "You don't have enough money."
          decor.owned(id, 1) shouldBe 2
          progress
              .build(id, store.getCharacter(id)!!, 1)
              .decorations
              .single()
              .quantity
              .toInt() shouldBe 2
        }
      }

      test("the trainer card packet carries badges, dex bits and play time") {
        runTest {
          val (store, id) = store()
          val progress = progressService(store)
          store.addPokemon(id, mon(id, 0, 258, 5))
          store.setStoryFlag(id, "hoenn/FLAG_BADGE03_GET")
          store.setStoryVar(id, ProgressKeys.PLAY_SECONDS, 3700)
          val p = progress.build(id, store.getCharacter(id)!!, 1)
          p.badges shouldBe (1 shl 10)
          p.isCaught(258) shouldBe true
          p.isSeen(258) shouldBe true
          p.playSeconds shouldBe 3700
        }
      }
    })
