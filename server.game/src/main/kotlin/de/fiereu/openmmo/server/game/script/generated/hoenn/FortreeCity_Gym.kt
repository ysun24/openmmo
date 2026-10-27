package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.FortreeCity_Gym
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
 * trainerbattle_single TRAINER_WINONA_1, FortreeCity_Gym_Text_WinonaIntro, FortreeCity_Gym_Text_WinonaDefeat, FortreeCity_Gym_EventScript_WinonaDefeated, NO_MUSIC
 * specialvar VAR_RESULT, ShouldTryRematchBattle
 * goto_if_eq VAR_RESULT, TRUE, FortreeCity_Gym_EventScript_WinonaRematch
 * goto_if_unset FLAG_RECEIVED_TM_AERIAL_ACE, FortreeCity_Gym_EventScript_GiveAerialAce2
 * msgbox FortreeCity_Gym_Text_WinonaPostBattle, MSGBOX_DEFAULT
 * release
 * end
 * ```
 */
internal object FortreeCity_Gym_EventScript_Winona : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_WINONA_1: badge 6, TM40
    val giveTm: suspend () -> Unit = giveTm@{
      if (!ctx.giveItem(Items.TM40)) return@giveTm
      ctx.say(FortreeCity_Gym.ExplainAerialAce)
      ctx.setFlag(HoennFlags.FLAG_RECEIVED_TM_AERIAL_ACE)
    }
    if (ctx.isFlagSet(HoennFlags.FLAG_DEFEATED_FORTREE_GYM)) {
      if (!ctx.isFlagSet(HoennFlags.FLAG_RECEIVED_TM_AERIAL_ACE)) giveTm()
      else ctx.say(FortreeCity_Gym.WinonaPostBattle)
      return
    }
    ctx.say(FortreeCity_Gym.WinonaIntro)
    if (ctx.trainerBattle(270) != BattleResult.VICTORY) return
    ctx.say(FortreeCity_Gym.WinonaDefeat)
    ctx.say(FortreeCity_Gym.ReceivedFeatherBadge)
    ctx.say(FortreeCity_Gym.ExplainFeatherBadgeTakeThis)
    ctx.setFlag(HoennFlags.FLAG_DEFEATED_FORTREE_GYM)
    ctx.setFlag(HoennFlags.FLAG_BADGE06_GET)
    giveTm()
    ctx.say(FortreeCity_Gym.RegisteredWinona)
    ctx.setFlag(HoennFlags.FLAG_ENABLE_WINONA_MATCH_CALL)
    ctx.setVar(HoennVars.VAR_SCOTT_FORTREE_CALL_STEP_COUNTER, 0)
    ctx.setFlag(HoennFlags.FLAG_SCOTT_CALL_FORTREE_GYM)
    ctx.badgeAwarded(6)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_JARED, FortreeCity_Gym_Text_JaredIntro, FortreeCity_Gym_Text_JaredDefeat
 * msgbox FortreeCity_Gym_Text_JaredPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object FortreeCity_Gym_EventScript_Jared : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_JARED
    ctx.gymTrainer(
        401,
        FortreeCity_Gym.JaredIntro,
        FortreeCity_Gym.JaredDefeat,
        FortreeCity_Gym.JaredPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_FLINT, FortreeCity_Gym_Text_FlintIntro, FortreeCity_Gym_Text_FlintDefeat
 * msgbox FortreeCity_Gym_Text_FlintPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object FortreeCity_Gym_EventScript_Flint : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_FLINT
    ctx.gymTrainer(
        654,
        FortreeCity_Gym.FlintIntro,
        FortreeCity_Gym.FlintDefeat,
        FortreeCity_Gym.FlintPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_ASHLEY, FortreeCity_Gym_Text_AshleyIntro, FortreeCity_Gym_Text_AshleyDefeat
 * msgbox FortreeCity_Gym_Text_AshleyPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object FortreeCity_Gym_EventScript_Ashley : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_ASHLEY
    ctx.gymTrainer(
        655,
        FortreeCity_Gym.AshleyIntro,
        FortreeCity_Gym.AshleyDefeat,
        FortreeCity_Gym.AshleyPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_EDWARDO, FortreeCity_Gym_Text_EdwardoIntro, FortreeCity_Gym_Text_EdwardoDefeat
 * msgbox FortreeCity_Gym_Text_EdwardoPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object FortreeCity_Gym_EventScript_Edwardo : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_EDWARDO
    ctx.gymTrainer(
        404,
        FortreeCity_Gym.EdwardoIntro,
        FortreeCity_Gym.EdwardoDefeat,
        FortreeCity_Gym.EdwardoPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_HUMBERTO, FortreeCity_Gym_Text_HumbertoIntro, FortreeCity_Gym_Text_HumbertoDefeat
 * msgbox FortreeCity_Gym_Text_HumbertoPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object FortreeCity_Gym_EventScript_Humberto : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_HUMBERTO
    ctx.gymTrainer(
        402,
        FortreeCity_Gym.HumbertoIntro,
        FortreeCity_Gym.HumbertoDefeat,
        FortreeCity_Gym.HumbertoPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_DARIUS, FortreeCity_Gym_Text_DariusIntro, FortreeCity_Gym_Text_DariusDefeat
 * msgbox FortreeCity_Gym_Text_DariusPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object FortreeCity_Gym_EventScript_Darius : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_DARIUS
    ctx.gymTrainer(
        803,
        FortreeCity_Gym.DariusIntro,
        FortreeCity_Gym.DariusDefeat,
        FortreeCity_Gym.DariusPostBattle)
  }
}

internal val FortreeCity_GymScripts: Map<String, Script> =
    mapOf(
        "FortreeCity_Gym_EventScript_Winona" to FortreeCity_Gym_EventScript_Winona,
        "FortreeCity_Gym_EventScript_Jared" to FortreeCity_Gym_EventScript_Jared,
        "FortreeCity_Gym_EventScript_Flint" to FortreeCity_Gym_EventScript_Flint,
        "FortreeCity_Gym_EventScript_Ashley" to FortreeCity_Gym_EventScript_Ashley,
        "FortreeCity_Gym_EventScript_Edwardo" to FortreeCity_Gym_EventScript_Edwardo,
        "FortreeCity_Gym_EventScript_Humberto" to FortreeCity_Gym_EventScript_Humberto,
        "FortreeCity_Gym_EventScript_Darius" to FortreeCity_Gym_EventScript_Darius,
    )
