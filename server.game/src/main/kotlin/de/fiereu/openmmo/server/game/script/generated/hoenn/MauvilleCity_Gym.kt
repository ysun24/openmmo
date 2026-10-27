package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.MauvilleCity_Gym
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.battle.BattleResult
import de.fiereu.openmmo.server.game.progression.badgeAwarded
import de.fiereu.openmmo.server.game.progression.gymTrainer
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext
import de.fiereu.openmmo.story.generated.hoenn.HoennFlags
import de.fiereu.openmmo.story.generated.hoenn.HoennVars

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_WATTSON_1, MauvilleCity_Gym_Text_WattsonIntro, MauvilleCity_Gym_Text_WattsonDefeat, MauvilleCity_Gym_EventScript_WattsonDefeated, NO_MUSIC
 * specialvar VAR_RESULT, ShouldTryRematchBattle
 * goto_if_eq VAR_RESULT, TRUE, MauvilleCity_Gym_EventScript_WattsonRematch
 * goto_if_unset FLAG_RECEIVED_TM_SHOCK_WAVE, MauvilleCity_Gym_EventScript_GiveShockWave2
 * goto_if_eq VAR_NEW_MAUVILLE_STATE, 2, MauvilleCity_Gym_EventScript_CompletedNewMauville
 * msgbox MauvilleCity_Gym_Text_WattsonPostBattle, MSGBOX_DEFAULT
 * release
 * end
 * ```
 */
internal object MauvilleCity_Gym_EventScript_Wattson : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_WATTSON_1: badge 3, TM34
    val giveTm: suspend () -> Unit = giveTm@{
      if (!ctx.giveItem(Items.TM34)) return@giveTm
      ctx.say(MauvilleCity_Gym.ExplainShockWave)
      ctx.setFlag(HoennFlags.FLAG_RECEIVED_TM_SHOCK_WAVE)
    }
    if (ctx.isFlagSet(HoennFlags.FLAG_DEFEATED_MAUVILLE_GYM)) {
      if (!ctx.isFlagSet(HoennFlags.FLAG_RECEIVED_TM_SHOCK_WAVE)) giveTm()
      else ctx.say(MauvilleCity_Gym.WattsonPostBattle)
      return
    }
    ctx.say(MauvilleCity_Gym.WattsonIntro)
    if (ctx.trainerBattle(267) != BattleResult.VICTORY) return
    ctx.say(MauvilleCity_Gym.WattsonDefeat)
    ctx.say(MauvilleCity_Gym.ReceivedDynamoBadge)
    ctx.say(MauvilleCity_Gym.ExplainDynamoBadgeTakeThis)
    ctx.setVar(HoennVars.VAR_SLATEPORT_OUTSIDE_MUSEUM_STATE, 3)
    ctx.clearFlag(HoennFlags.FLAG_HIDE_VERDANTURF_TOWN_SCOTT)
    ctx.setFlag(HoennFlags.FLAG_DEFEATED_MAUVILLE_GYM)
    ctx.setFlag(HoennFlags.FLAG_BADGE03_GET)
    ctx.setVar(HoennVars.VAR_PETALBURG_GYM_STATE, ctx.getVar(HoennVars.VAR_PETALBURG_GYM_STATE) + 1)
    giveTm()
    ctx.say(MauvilleCity_Gym.RegisteredWattson)
    ctx.setFlag(HoennFlags.FLAG_ENABLE_WATTSON_MATCH_CALL)
    ctx.badgeAwarded(3)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_SHAWN, MauvilleCity_Gym_Text_ShawnIntro, MauvilleCity_Gym_Text_ShawnDefeat
 * msgbox MauvilleCity_Gym_Text_ShawnPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object MauvilleCity_Gym_EventScript_Shawn : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_SHAWN
    ctx.gymTrainer(
        194,
        MauvilleCity_Gym.ShawnIntro,
        MauvilleCity_Gym.ShawnDefeat,
        MauvilleCity_Gym.ShawnPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_VIVIAN, MauvilleCity_Gym_Text_VivianIntro, MauvilleCity_Gym_Text_VivianDefeat
 * msgbox MauvilleCity_Gym_Text_VivianPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object MauvilleCity_Gym_EventScript_Vivian : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_VIVIAN
    ctx.gymTrainer(
        649,
        MauvilleCity_Gym.VivianIntro,
        MauvilleCity_Gym.VivianDefeat,
        MauvilleCity_Gym.VivianPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_BEN, MauvilleCity_Gym_Text_BenIntro, MauvilleCity_Gym_Text_BenDefeat
 * msgbox MauvilleCity_Gym_Text_BenPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object MauvilleCity_Gym_EventScript_Ben : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_BEN
    ctx.gymTrainer(
        323, MauvilleCity_Gym.BenIntro, MauvilleCity_Gym.BenDefeat, MauvilleCity_Gym.BenPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_KIRK, MauvilleCity_Gym_Text_KirkIntro, MauvilleCity_Gym_Text_KirkDefeat
 * msgbox MauvilleCity_Gym_Text_KirkPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object MauvilleCity_Gym_EventScript_Kirk : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_KIRK
    ctx.gymTrainer(
        191,
        MauvilleCity_Gym.KirkIntro,
        MauvilleCity_Gym.KirkDefeat,
        MauvilleCity_Gym.KirkPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_ANGELO, MauvilleCity_Gym_Text_AngeloIntro, MauvilleCity_Gym_Text_AngeloDefeat
 * msgbox MauvilleCity_Gym_Text_AngeloPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object MauvilleCity_Gym_EventScript_Angelo : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_ANGELO
    ctx.gymTrainer(
        802,
        MauvilleCity_Gym.AngeloIntro,
        MauvilleCity_Gym.AngeloDefeat,
        MauvilleCity_Gym.AngeloPostBattle)
  }
}

internal val MauvilleCity_GymScripts: Map<String, Script> =
    mapOf(
        "MauvilleCity_Gym_EventScript_Wattson" to MauvilleCity_Gym_EventScript_Wattson,
        "MauvilleCity_Gym_EventScript_Shawn" to MauvilleCity_Gym_EventScript_Shawn,
        "MauvilleCity_Gym_EventScript_Vivian" to MauvilleCity_Gym_EventScript_Vivian,
        "MauvilleCity_Gym_EventScript_Ben" to MauvilleCity_Gym_EventScript_Ben,
        "MauvilleCity_Gym_EventScript_Kirk" to MauvilleCity_Gym_EventScript_Kirk,
        "MauvilleCity_Gym_EventScript_Angelo" to MauvilleCity_Gym_EventScript_Angelo,
    )
