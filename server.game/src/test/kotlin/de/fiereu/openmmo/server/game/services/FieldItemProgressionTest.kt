package de.fiereu.openmmo.server.game.services

import de.fiereu.network.PacketEvent
import de.fiereu.openmmo.common.MonStatus
import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.PokemonMove
import de.fiereu.openmmo.common.enums.CharacterGender
import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.items.ItemDef
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.net.game.packets.battle.EvolutionReplyPacket
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import de.fiereu.openmmo.progression.Gen3Data
import de.fiereu.openmmo.server.game.battle.ExpCurves
import de.fiereu.openmmo.server.game.progression.PlayerProgressService
import de.fiereu.openmmo.server.game.storage.CharacterStore
import de.fiereu.openmmo.server.game.storage.EntityIdService
import de.fiereu.openmmo.server.game.testsupport.FakeCharacterRepository
import de.fiereu.openmmo.server.game.testsupport.FakeSession
import de.fiereu.openmmo.server.game.testsupport.evolutionService
import de.fiereu.openmmo.server.game.testsupport.fieldItemService
import de.fiereu.openmmo.server.game.testsupport.progressService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/** The Gen 3 item_use.c rules added in r3-items, item by item. */
@OptIn(ExperimentalCoroutinesApi::class)
class FieldItemProgressionTest :
    FunSpec({
      val items = ItemRegistry()
      val species = SpeciesRegistry()

      class Fx(
          val store: CharacterStore,
          val service: FieldItemService,
          val progress: PlayerProgressService,
          val charId: Long,
          val monId: Long,
          val evo: EvolutionService,
      ) {
        /** Uses the item and plays any evolution scene it starts to the end (A pressed). */
        suspend fun useAndEvolve(item: ItemDef): FieldItemService.Outcome {
          val out = use(item)
          val (id, to) = out.evolveTo ?: return out
          val session = FakeSession(charId)
          evo.startEvolution(session, charId, id, to, consumeItem = out.consumeOnEvolve)
          evo.onReply(PacketEvent(EvolutionReplyPacket(id, proceed = true), session))
          val texts =
              session.sent
                  .filterIsInstance<de.fiereu.openmmo.net.game.packets.ChatMessagePacket>()
                  .map { it.message }
          return out.copy(more = out.more + texts)
        }

        fun mon(): Pokemon = store.getCharacter(charId)!!.pokemon.first { it.id == monId }

        fun held(item: ItemDef) = store.getCharacter(charId)!!.items[items.idOf(item)] ?: 0

        suspend fun use(item: ItemDef, slot: Int = -1) =
            service.use(charId, items.idOf(item), monId, moveSlot = slot)
      }

      suspend fun TestScope.fx(
          dex: Int,
          level: Int,
          bag: Map<ItemDef, Int>,
          change: (Pokemon) -> Pokemon = { it },
      ): Fx {
        val store = CharacterStore(FakeCharacterRepository(), EntityIdService(), backgroundScope)
        val charId = store.createCharacter(1, "Red", CharacterGender.MALE, Region.KANTO).info.id
        val def = species.get(dex)!!
        val base = partyMon(charId, 0, hp = 1, dexId = dex, level = level)
        val mon = change(base.copy(xp = ExpCurves.totalXpFor(def.growthRate, level)))
        store.addPokemon(charId, mon)
        bag.forEach { (item, qty) -> store.addItem(charId, items.idOf(item), qty) }
        val progress = progressService(store)
        val evo = evolutionService(store)
        return Fx(
            store,
            fieldItemService(store, progress = progress, evolution = evo),
            progress,
            charId,
            mon.id,
            evo)
      }

      test("Antidote cures poison, Burn Heal leaves poison alone, Full Heal cures anything") {
        runTest {
          val f =
              fx(1, 10, mapOf(Items.ANTIDOTE to 1, Items.BURN_HEAL to 1, Items.FULL_HEAL to 1)) {
                it.copy(status = MonStatus.POISON)
              }
          f.use(Items.BURN_HEAL).changed shouldBe false
          f.held(Items.BURN_HEAL) shouldBe 1
          val out = f.use(Items.ANTIDOTE)
          out.changed shouldBe true
          out.text shouldContain "poisoning"
          f.mon().status shouldBe 0
          f.store.updatePokemon(f.charId, f.mon().copy(status = 3))
          f.use(Items.FULL_HEAL).text shouldContain "woke up"
          f.mon().status shouldBe 0
        }
      }

      test("berries cure their own status, Lum cures every one") {
        runTest {
          val f =
              fx(1, 10, mapOf(Items.CHERI_BERRY to 1, Items.LUM_BERRY to 1)) {
                it.copy(status = MonStatus.BURN)
              }
          f.use(Items.CHERI_BERRY).changed shouldBe false
          f.use(Items.LUM_BERRY).changed shouldBe true
          f.mon().status shouldBe 0
        }
      }

      test("Ether needs a chosen move and restores 10 PP to it only") {
        runTest {
          val f = fx(4, 10, mapOf(Items.ETHER to 1))
          f.use(Items.ETHER).text shouldBe "Choose a move."
          f.use(Items.ETHER, slot = 0).changed shouldBe true
          f.mon().moves.map { it.pp.toInt() } shouldBe listOf(13, 40)
          f.held(Items.ETHER) shouldBe 0
        }
      }

      test("Max Ether fills a move; a full move has no effect") {
        runTest {
          val f = fx(4, 10, mapOf(Items.MAX_ETHER to 2))
          f.use(Items.MAX_ETHER, slot = 0).changed shouldBe true
          f.mon().moves[0].pp.toInt() shouldBe 35
          f.use(Items.MAX_ETHER, slot = 0).changed shouldBe false
          f.held(Items.MAX_ETHER) shouldBe 1
        }
      }

      test("PP Up raises one move's max PP by a fifth, three times at most; PP Max goes to 3") {
        runTest {
          val f = fx(4, 10, mapOf(Items.PP_UP to 4, Items.PP_MAX to 1))
          f.use(Items.PP_UP, slot = 0).changed shouldBe true
          f.mon().ppBonuses shouldBe 1
          // Scratch: 35 base, +7 per PP Up, and the current PP rises with it.
          f.mon().moves[0].pp.toInt() shouldBe 3 + 7
          f.use(Items.PP_UP, slot = 0)
          f.use(Items.PP_UP, slot = 0)
          f.mon().ppBonuses shouldBe 3
          MonStatus.maxPp(35, f.mon().ppBonuses, 0) shouldBe 56
          f.use(Items.PP_UP, slot = 0).changed shouldBe false
          f.use(Items.PP_MAX, slot = 1).changed shouldBe true
          (f.mon().ppBonuses shr 2) and 3 shouldBe 3
        }
      }

      test(
          "Rare Candy levels up with the exp floor, learns a level move, and HP keeps the damage") {
            runTest {
              val f = fx(1, 6, mapOf(Items.RARE_CANDY to 1))
              val before = f.mon()
              val out = f.use(Items.RARE_CANDY)
              out.changed shouldBe true
              out.lines() shouldContain "BULBASAUR grew to Lv. 7!"
              val after = f.mon()
              after.level.toInt() shouldBe 7
              after.xp shouldBe ExpCurves.totalXpFor(species.get(1)!!.growthRate, 7)
              // Leech Seed (73) at level 7.
              after.moves.map { it.id.toInt() } shouldContain 73
              after.hp shouldBe (before.hp + (fieldMaxHp(after) - fieldMaxHp(before))).toShort()
            }
          }

      test("Rare Candy at level 100 has no effect") {
        runTest {
          val f = fx(1, 100, mapOf(Items.RARE_CANDY to 1))
          f.use(Items.RARE_CANDY).text shouldBe "It won't have any effect."
          f.held(Items.RARE_CANDY) shouldBe 1
        }
      }

      test("Rare Candy into an evolution level evolves (Charmander 15 -> Charmeleon)") {
        runTest {
          val f = fx(4, 15, mapOf(Items.RARE_CANDY to 1))
          val out = f.useAndEvolve(Items.RARE_CANDY)
          out.lines().any { "evolved into CHARMELEON" in it } shouldBe true
          f.mon().dexId shouldBe 5
          f.store.getCharacter(f.charId)!!.dexCaught shouldContain 5
        }
      }

      test("a full moveset queues the level-up move for the player to place") {
        runTest {
          val f =
              fx(1, 6, mapOf(Items.RARE_CANDY to 1)) {
                it.copy(
                    moves =
                        listOf(
                            PokemonMove(33, 35),
                            PokemonMove(45, 40),
                            PokemonMove(22, 10),
                            PokemonMove(77, 35)))
              }
          f.use(Items.RARE_CANDY)
          f.progress.currentLearn(f.charId)!!.moveId.toInt() shouldBe 73
          f.service.answerLearn(f.charId, 0)!! shouldContain "learned LEECH SEED"
          f.mon().moves[0].id.toInt() shouldBe 73
          f.progress.currentLearn(f.charId) shouldBe null
        }
      }

      test("an evolution stone evolves the species it fits and nothing else") {
        runTest {
          val f = fx(37, 20, mapOf(Items.FIRE_STONE to 1, Items.WATER_STONE to 1))
          f.use(Items.WATER_STONE).text shouldBe "It won't have any effect."
          f.held(Items.WATER_STONE) shouldBe 1
          f.useAndEvolve(Items.FIRE_STONE).lines().any { "evolved into NINETALES" in it } shouldBe
              true
          f.mon().dexId shouldBe 38
          f.held(Items.FIRE_STONE) shouldBe 0
        }
      }

      test("Eevee takes three stones (Thunderstone -> Jolteon)") {
        runTest {
          val f = fx(133, 20, mapOf(Items.THUNDERSTONE to 1))
          f.useAndEvolve(Items.THUNDERSTONE).evolveTo?.second shouldBe 135
          f.mon().dexId shouldBe 135
        }
      }

      test("vitamins add 10 EVs up to 100 in a stat; EV berries take 10 off") {
        runTest {
          val f = fx(1, 50, mapOf(Items.HP_UP to 12, Items.POMEG_BERRY to 1))
          f.use(Items.HP_UP).changed shouldBe true
          f.mon().eVs.hp shouldBe 10
          repeat(9) { f.use(Items.HP_UP) }
          f.mon().eVs.hp shouldBe 100
          f.use(Items.HP_UP).text shouldBe "It won't have any effect."
          f.held(Items.HP_UP) shouldBe 2
          f.use(Items.POMEG_BERRY).changed shouldBe true
          f.mon().eVs.hp shouldBe 90
        }
      }

      test("a TM teaches the Gen 3 move of its number to a compatible species and is used up") {
        runTest {
          // TM06 is TOXIC in Gen 3 (Gen 5: also Toxic); TM01 FOCUS PUNCH, not Hone Claws.
          Gen3Data.tmhmMove(0) shouldBe 264
          val f = fx(1, 20, mapOf(Items.TM06 to 1, Items.TM01 to 1))
          f.use(Items.TM01).text shouldContain "can't learn"
          val out = f.use(Items.TM06)
          out.text shouldContain "learned TOXIC"
          f.mon().moves.map { it.id.toInt() } shouldContain 92
          f.held(Items.TM06) shouldBe 0
        }
      }

      test("a TM on four known moves asks which to forget; HMs are kept and can't be forgotten") {
        runTest {
          val f =
              fx(1, 20, mapOf(Items.TM06 to 1, Items.HM01 to 1)) {
                it.copy(
                    moves =
                        listOf(
                            PokemonMove(15, 30),
                            PokemonMove(45, 40),
                            PokemonMove(22, 10),
                            PokemonMove(77, 35)))
              }
          f.use(Items.TM06).text shouldContain "Choose a move to forget"
          f.use(Items.TM06, slot = 0).text shouldContain "HM moves can't be forgotten"
          f.use(Items.TM06, slot = 1).text shouldContain "forgot GROWL"
          f.mon().moves[1].id.toInt() shouldBe 92
          // CUT is already known; HM01 is not used up either way.
          f.held(Items.HM01) shouldBe 1
        }
      }

      test("Escape Rope only works in a cave") {
        runTest {
          val f = fx(1, 10, mapOf(Items.ESCAPE_ROPE to 1))
          f.service.use(f.charId, items.idOf(Items.ESCAPE_ROPE), 0).text shouldBe
              "Can't use that here."
          f.held(Items.ESCAPE_ROPE) shouldBe 1
        }
      }

      test("eggs take no items") {
        runTest {
          val f = fx(1, 5, mapOf(Items.RARE_CANDY to 1)) { it.copy(isEgg = true) }
          f.use(Items.RARE_CANDY).text shouldBe "It won't have any effect."
        }
      }
    })

private fun fieldMaxHp(mon: Pokemon): Int =
    de.fiereu.openmmo.server.game.battle.StatCalculator.computeAll(
            SpeciesRegistry().get(mon.dexId)!!, mon)
        .hp
