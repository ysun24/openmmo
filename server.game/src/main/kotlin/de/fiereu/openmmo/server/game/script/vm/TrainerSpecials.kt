package de.fiereu.openmmo.server.game.script.vm

import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.server.game.battle.BattleResult
import de.fiereu.openmmo.server.game.field.Rematches
import de.fiereu.openmmo.server.game.script.ScriptContext
import de.fiereu.openmmo.trainer.RematchTable
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.put

/**
 * battle_setup.c's script side: the trainer battle std scripts
 * (EventScript_TryDoNormalTrainerBattle and friends) call these with the battle `trainerbattle`
 * configured, and the story's scripted battles (legendaries, the Regis, Lati@s, Marowak's ghost,
 * the catching tutorials).
 */
@Singleton
class TrainerSpecials @Inject constructor() : SpecialTable() {
  private fun vm(ctx: ScriptContext) = checkNotNull(ctx.vm) { "special outside a script" }

  init {
    on("GetTrainerFlag") { ctx ->
      val vm = vm(ctx)
      val t = vm.state.trainer ?: return@on 0
      if (ctx.flagGet(trainerFlag(ctx, t.opponentA))) 1 else 0
    }
    on("ShowTrainerIntroSpeech") { ctx ->
      val vm = vm(ctx)
      val t = vm.state.trainer ?: return@on 0
      vm.showMessage(
          vm.textAt(if (t.introB != 0 && t.opponentB != 0 && t.introA == 0) t.introB else t.introA))
      0
    }
    on("ShowTrainerCantBattleSpeech") { ctx ->
      val vm = vm(ctx)
      vm.showMessage(vm.textAt(vm.state.trainer?.cannotBattle ?: 0))
      0
    }
    on("PlayTrainerEncounterMusic") { ctx ->
      val vm = vm(ctx)
      val t = vm.state.trainer ?: return@on 0
      val music = ctx.services?.trainers?.get(vm.region, t.opponentA)?.music.orEmpty()
      ctx.ui("sound") {
        put("kind", "encounter")
        put("name", music)
      }
      0
    }
    noop(
        "SetTrainerFacingDirection",
        "SetUpTrainerMovement",
        "DoTrainerApproach",
        "EndTrainerApproach")
    on("TryPrepareSecondApproachingTrainer") { ctx ->
      ctx.setResult(0)
      0
    }
    on("GetTrainerBattleMode") { ctx -> vm(ctx).state.trainer?.mode ?: 0 }
    on("HasEnoughMonsForDoubleBattle") { ctx ->
      val party = ctx.vmParty().filter { !it.isEgg }
      val usable = party.count { it.hp > 0 }
      ctx.setResult(
          when {
            usable >= 2 -> PLAYER_HAS_TWO_USABLE_MONS
            party.size <= 1 -> PLAYER_HAS_ONE_MON
            else -> PLAYER_HAS_ONE_USABLE_MON
          })
      0
    }
    on("ShouldTryRematchBattle") { ctx -> if (rematchReady(ctx)) 1 else 0 }
    on("IsTrainerReadyForRematch") { ctx -> if (rematchReady(ctx)) 1 else 0 }
    on("BattleSetup_StartRematchBattle", "StartRematchBattle") { ctx ->
      val vm = vm(ctx)
      val t = vm.state.trainer ?: return@on 0
      val id = rematchTrainer(ctx, t.opponentA) ?: t.opponentA
      vm.state.trainer = t.copy(opponentA = id)
      vm.doTrainerBattle()
      ctx.clearFlag(Rematches.readyFlag(vm.region, t.opponentA))
      ctx.flagSet(trainerFlag(ctx, t.opponentA))
      0
    }
    on("ShouldTryGetTrainerScript") { ctx -> 0 }
    on("PlayerFaceTrainerAfterBattle") { ctx ->
      val vm = vm(ctx)
      val t = vm.state.trainer ?: return@on 0
      val npc = vm.poseOf(t.localId) ?: return@on 0
      val me = vm.poseOf(ScriptVm.OBJ_PLAYER) ?: return@on 0
      val d =
          if (npc.x > me.x) de.fiereu.openmmo.common.enums.Direction.RIGHT
          else if (npc.x < me.x) de.fiereu.openmmo.common.enums.Direction.LEFT
          else if (npc.y < me.y) de.fiereu.openmmo.common.enums.Direction.UP
          else de.fiereu.openmmo.common.enums.Direction.DOWN
      ctx.vmTurnSelf(d)
      0
    }
    on("SetBattledTrainerFlag") { ctx ->
      vm(ctx).state.trainer?.let { ctx.flagSet(trainerFlag(ctx, it.opponentA)) }
      0
    }
    on("IsTrainerRegistered") { ctx ->
      val id = ctx.specialVar(4)
      if (ctx.isFlagSet("hoenn/OPENMMO_MATCH_CALL_$id")) 1 else 0
    }
    on("Script_HasTrainerBeenFought") { ctx ->
      if (ctx.flagGet(trainerFlag(ctx, ctx.specialVar(4)))) 1 else 0
    }
    on("GetBattleOutcome") { ctx -> vm(ctx).state.battleOutcome }

    // Scripted wild battles: the mon setwildbattle (or the special itself) put in gEnemyParty.
    on(
        "BattleSetup_StartLegendaryBattle",
        "StartLegendaryBattle",
        "StartRegiBattle",
        "BattleSetup_StartLatiBattle",
        "StartGroudonKyogreBattle",
        "StartSouthernIslandBattle",
    ) { ctx ->
      wildBattle(ctx)
      0
    }
    // FRLG Pokemon Tower: the ghost Marowak; without the Silph Scope it cannot even be fought.
    on("StartMarowakBattle") { ctx ->
      val vm = vm(ctx)
      val scope = ctx.vmItemCount(vm.img.constOrNull("ITEM_SILPH_SCOPE") ?: 0) > 0
      if (!scope) {
        vm.state.battleOutcome = ScriptVm.B_OUTCOME_RAN
      } else {
        vm.state.wild = Triple(MAROWAK, 30, 0)
        wildBattle(ctx)
      }
      // CB2_EndMarowakBattle: FALSE once the ghost is beaten, TRUE pushes the player back.
      ctx.setResult(if (vm.state.battleOutcome == ScriptVm.B_OUTCOME_WON) 0 else 1)
      0
    }
    // The catching tutorials (the old man in Viridian, Wally on Route 102): shown, not fought.
    on("StartOldManTutorialBattle", "StartWallyTutorialBattle") { ctx ->
      vm(ctx).state.battleOutcome = ScriptVm.B_OUTCOME_CAUGHT
      0
    }
    on("LoadWallyZigzagoon") { ctx -> 0 }
    // Emerald's DoSpecialTrainerBattle: the multi battle with Steven in the Space Center.
    on("DoSpecialTrainerBattle") { ctx ->
      val vm = vm(ctx)
      if (ctx.specialVar(4) == SPECIAL_BATTLE_STEVEN) {
        val r = ctx.trainerBattle(vm.img.constOrNull("TRAINER_STEVEN") ?: STEVEN_PARTNER_FALLBACK)
        val magma =
            listOf("TRAINER_GRUNT_MOSSDEEP", "TRAINER_TABITHA_MOSSDEEP", "TRAINER_MAXIE_MOSSDEEP")
        var outcome = vm.outcome(r)
        if (r == BattleResult.VICTORY) {
          for (t in magma.mapNotNull { vm.img.constOrNull(it) }) {
            val rr = ctx.trainerBattle(t)
            outcome = vm.outcome(rr)
            if (rr != BattleResult.VICTORY) break
          }
        }
        vm.state.battleOutcome = outcome
      }
      0
    }
    on("StartSpecialBattle") { ctx -> 0 }
    on("RockSmashWildEncounter") { ctx ->
      // RockSmashWildEncounter: 1 in 5 smashed rocks hide a ROCK_SMASH wild mon.
      ctx.setResult(0)
      0
    }
    on("TryFieldPoisonWhiteOut") { ctx ->
      ctx.setResult(0)
      0
    }
    noop(
        "SetCB2WhiteOut",
        "OffsetCameraForBattle",
        "VsSeekerFreezeObjectsAfterChargeComplete",
        "VsSeekerResetObjectMovementAfterChargeComplete")
    on("OverworldWhiteOutGetMoneyLoss") { ctx -> 0 }
    // Secret base battles (r4 owns the bases; the battle bookkeeping is script flags).
    noop(
        "PrepSecretBaseBattleFlags",
        "DeclinedSecretBaseBattle",
        "WonSecretBaseBattle",
        "LostSecretBaseBattle",
        "DrewSecretBaseBattle")
  }

  private suspend fun wildBattle(ctx: ScriptContext) {
    val vm = vm(ctx)
    val w = vm.state.wild ?: return
    vm.closeMessage()
    val r = ctx.vmWildBattle(w.first, w.second, w.third, legendary = true)
    vm.state.battleOutcome = vm.outcome(r)
  }

  private fun trainerFlag(ctx: ScriptContext, trainer: Int): Int =
      (ctx.constant("TRAINER_FLAGS_START") ?: 0x500) + trainer

  private fun rematchReady(ctx: ScriptContext): Boolean {
    val vm = ctx.vm ?: return false
    val t = vm.state.trainer ?: return false
    if (vm.region != Region.HOENN) {
      // FRLG: the VS Seeker marks trainers ready (FieldService.rematchReady per object).
      return ctx.state.rematchReady.containsKey(npcIdx(t.localId))
    }
    return ctx.isFlagSet(Rematches.readyFlag(Region.HOENN, t.opponentA))
  }

  private fun rematchTrainer(ctx: ScriptContext, first: Int): Int? {
    val vm = ctx.vm ?: return null
    ctx.state.rematchReady[npcIdx(vm.state.trainer?.localId ?: 0)]?.let {
      return it
    }
    val entry = RematchTable.byFirst(vm.region, first) ?: return null
    return Rematches.emeraldRematchTrainer(entry) { ctx.flagGet(trainerFlag(ctx, it)) }
  }

  private companion object {
    const val PLAYER_HAS_TWO_USABLE_MONS = 0
    const val PLAYER_HAS_ONE_MON = 1
    const val PLAYER_HAS_ONE_USABLE_MON = 2
    const val MAROWAK = 105
    const val SPECIAL_BATTLE_STEVEN = 7
    const val STEVEN_PARTNER_FALLBACK = 0
  }
}

/** Sets gSpecialVar_Result explicitly (also to 0, which a plain `special` return leaves alone). */
fun ScriptContext.setResult(v: Int) {
  result = v
}
