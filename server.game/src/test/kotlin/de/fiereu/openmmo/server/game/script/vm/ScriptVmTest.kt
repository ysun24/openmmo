package de.fiereu.openmmo.server.game.script.vm

import de.fiereu.openmmo.common.enums.CharacterGender
import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.maps.MapManager
import de.fiereu.openmmo.net.game.packets.dialog.DialogActionPacket
import de.fiereu.openmmo.net.game.packets.dialog.DialogActionResponsePacket
import de.fiereu.openmmo.server.game.script.MovementStep
import de.fiereu.openmmo.server.game.script.ScriptContext
import de.fiereu.openmmo.server.game.services.DialogService
import de.fiereu.openmmo.server.game.services.NpcService
import de.fiereu.openmmo.server.game.services.ScriptMovementService
import de.fiereu.openmmo.server.game.services.StoryService
import de.fiereu.openmmo.server.game.session.PENDING_DIALOG
import de.fiereu.openmmo.server.game.session.PENDING_DIALOG_RESPONSE
import de.fiereu.openmmo.server.game.session.PLAYER_STATE
import de.fiereu.openmmo.server.game.storage.CharacterStore
import de.fiereu.openmmo.server.game.storage.EntityIdService
import de.fiereu.openmmo.server.game.testsupport.FakeCharacterRepository
import de.fiereu.openmmo.server.game.testsupport.FakeSession
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/** The interpreter's control flow and core commands, on the real assembled scripts. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScriptVmTest :
    FunSpec({
      class Rig(val session: FakeSession, val ctx: ScriptContext, val store: CharacterStore)

      suspend fun TestScope.rig(region: Region, bank: Int = 0, map: Int = 0): Rig {
        val store = CharacterStore(FakeCharacterRepository(), EntityIdService(), backgroundScope)
        val charId = store.createCharacter(1, "Red", CharacterGender.MALE, region).info.id
        val session =
            FakeSession(
                characterId = charId,
                regionId = region.wireValue.toInt(),
                bankId = bank,
                mapId = map)
        val state = session.attributes[PLAYER_STATE]!!
        val maps = MapManager()
        val movement = ScriptMovementService(maps, NpcService(maps, store), store)
        val ctx = ScriptContext(session, state, -1, DialogService(), StoryService(store), movement)
        return Rig(session, ctx, store)
      }

      /** Runs [label], answering every box (yes to yes/no) until the script ends. */
      suspend fun TestScope.run(r: Rig, region: String, label: String, immediate: Boolean = false) {
        val img = ScriptImage.of(region)!!
        val job = launch { ScriptVm(r.ctx, img, immediate).runLabel(label) }
        while (job.isActive) {
          advanceUntilIdle()
          r.session.attributes.remove(PENDING_DIALOG)?.complete(Unit)
          r.session.attributes
              .remove(PENDING_DIALOG_RESPONSE)
              ?.complete(DialogActionResponsePacket(0, 1))
          advanceUntilIdle()
        }
      }

      fun Rig.texts() = session.sent.filterIsInstance<DialogActionPacket>().map { it.textId }

      fun textId(region: String, label: String): Int {
        val img = ScriptImage.of(region)!!
        return img.texts.getValue(img.labels.getValue(label)).textId
      }

      test("GetQuestLogState clears VAR_RESULT (goto_if_questlog after a menu answer of 2)") {
        runTest {
          val r = rig(Region.KANTO)
          r.ctx.varSet("VAR_RESULT", 2)
          WorldSpecials().run(r.ctx, "GetQuestLogState")
          r.ctx.varGet("VAR_RESULT") shouldBe 0
        }
      }

      test("msgbox shows the label's own text") {
        runTest {
          val r = rig(Region.KANTO)
          run(r, "kanto", "CeladonCity_Gym_EventScript_GymStatue")
          r.texts() shouldBe listOf(textId("kanto", "CeladonCity_Gym_Text_GymStatue"))
        }
      }

      test("goto_if_set follows the flag") {
        runTest {
          val r = rig(Region.KANTO)
          r.ctx.setFlag("kanto/FLAG_BADGE04_GET")
          run(r, "kanto", "CeladonCity_Gym_EventScript_GymStatue")
          r.texts() shouldBe listOf(textId("kanto", "CeladonCity_Gym_Text_GymStatuePlayerWon"))
        }
      }

      test("goto_if_unset takes the branch when the flag is clear") {
        runTest {
          val r = rig(Region.KANTO)
          run(r, "kanto", "CeladonCity_GameCorner_EventScript_BuyCoins")
          r.texts().size shouldBe 1
          r.texts().single() shouldNotBe 0
        }
      }

      test("map scripts run immediately and set their flags") {
        runTest {
          val r = rig(Region.KANTO, 4, 3)
          run(r, "kanto", "PalletTown_ProfessorOaksLab_OnTransition", immediate = true)
          r.ctx.isFlagSet("kanto/FLAG_VISITED_OAKS_LAB") shouldBe true
          r.session.sent.filterIsInstance<DialogActionPacket>().size shouldBe 0
        }
      }

      test("VarGet returns literals, special vars and story vars") {
        runTest {
          val r = rig(Region.HOENN)
          val vm = ScriptVm(r.ctx, ScriptImage.of("hoenn")!!)
          vm.varGet(123) shouldBe 123
          vm.varSet(0x8004, 77)
          vm.varGet(0x8004) shouldBe 77
          val id = ScriptImage.of("hoenn")!!.const("VARS_START") + 0x50
          vm.varSet(id, 0x1_0005)
          vm.varGet(id) shouldBe 5
          vm.flagSet(0x4001)
          vm.flagGet(0x4001) shouldBe true
          vm.flagClear(0x4001)
          vm.flagGet(0x4001) shouldBe false
        }
      }

      test("r5 merge: one accessor for the temp vars, by id and by decomp name") {
        runTest {
          val r = rig(Region.HOENN)
          // Without the VM (a hand-ported script) the temp vars live in the context.
          r.ctx.varSet("VAR_0x8004", 9)
          r.ctx.specialVar(4) shouldBe 9
          r.ctx.varSet("VAR_ITEM_ID", 133)
          r.ctx.varGet(0x800E) shouldBe 133
          r.ctx.result = 1
          r.ctx.varGet("VAR_RESULT") shouldBe 1
          // Inside the VM they are its gSpecialVar_* slots, whichever name a lane uses.
          val vm = ScriptVm(r.ctx, ScriptImage.of("hoenn")!!)
          r.ctx.vm = vm
          r.ctx.varSet("VAR_ITEM_ID", 140)
          vm.varGet(0x800E) shouldBe 140
          r.ctx.setSpecialVar(5, 3)
          r.ctx.varGet("VAR_0x8005") shouldBe 3
          r.ctx.varSet("VAR_CONTEST_RANK", 2)
          vm.varGet(r.ctx.varId("VAR_CONTEST_RANK")!!) shouldBe 2
        }
      }

      test("trainer flags are the field service's defeated keys") {
        runTest {
          val r = rig(Region.HOENN)
          val img = ScriptImage.of("hoenn")!!
          val vm = ScriptVm(r.ctx, img)
          vm.flagSet(img.const("TRAINER_FLAGS_START") + 320)
          r.ctx.isFlagSet("hoenn/TRAINER_DEFEATED_320") shouldBe true
        }
      }

      test("trainerbattle with an event script continues into it after the battle") {
        runTest {
          val r = rig(Region.HOENN)
          val img = ScriptImage.of("hoenn")!!
          val vm = ScriptVm(r.ctx, img)
          val label = img.labels.getValue("Route102_EventScript_Calvin")
          img.commands[img.u8(label)] shouldBe "trainerbattle"
          val next = vm.configureTrainerBattle(label + 1, approaching = false)
          next shouldBe img.labels["EventScript_TryDoNormalTrainerBattle"]
          val t = vm.state.trainer!!
          t.mode shouldBe ScriptVm.MODE_CONTINUE_SCRIPT
          t.opponentA shouldBe img.const("TRAINER_CALVIN_1")
          t.scriptRetA shouldBe
              img.labels["Route102_EventScript_CalvinRegisterMatchCallAfterBattle"]
          img.texts[t.introA]?.label shouldBe "Route102_Text_CalvinIntro"
        }
      }

      test("movement scripts map onto the client's steps") {
        VmMovement.ops("MOVEMENT_ACTION_WALK_FAST_DOWN") shouldBe
            listOf(MoveOp.Step(MovementStep.FAST_DOWN))
        VmMovement.ops("MOVEMENT_ACTION_WALK_SLOWER_LEFT") shouldBe
            listOf(MoveOp.Step(MovementStep.WALK_LEFT))
        VmMovement.ops("MOVEMENT_ACTION_JUMP_2_UP") shouldBe
            listOf(MoveOp.Step(MovementStep.FAST_UP), MoveOp.Step(MovementStep.FAST_UP))
        VmMovement.ops("MOVEMENT_ACTION_FACE_LEFT_FAST") shouldBe
            listOf(MoveOp.Step(MovementStep.FACE_LEFT))
        VmMovement.ops("MOVEMENT_ACTION_EMOTE_EXCLAMATION_MARK") shouldBe
            listOf(MoveOp.Emote("exclamation_mark"))
        VmMovement.ops("MOVEMENT_ACTION_DISABLE_ANIMATION") shouldBe emptyList()
      }

      test("std scripts are reachable from the table") {
        val img = ScriptImage.of("kanto")!!
        val table = img.labels.getValue("gStdScripts")
        img.labelsAt(img.u32(table + 4 * 4)).isNotEmpty() shouldBe true
      }
    })
