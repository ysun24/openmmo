package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.SootopolisCity_Gym_1F
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.battle.BattleResult
import de.fiereu.openmmo.server.game.progression.badgeAwarded
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext
import de.fiereu.openmmo.story.generated.hoenn.HoennFlags
import de.fiereu.openmmo.story.generated.hoenn.HoennVars

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_JUAN_1, SootopolisCity_Gym_1F_Text_JuanIntro, SootopolisCity_Gym_1F_Text_JuanDefeat, SootopolisCity_Gym_1F_EventScript_JuanDefeated, NO_MUSIC
 * specialvar VAR_RESULT, ShouldTryRematchBattle
 * goto_if_eq VAR_RESULT, TRUE, SootopolisCity_Gym_1F_EventScript_JuanRematch
 * goto_if_unset FLAG_RECEIVED_TM_WATER_PULSE, SootopolisCity_Gym_1F_EventScript_GiveWaterPulse2
 * goto_if_unset FLAG_BADGE06_GET, SootopolisCity_Gym_1F_EventScript_GoGetFortreeBadge
 * msgbox SootopolisCity_Gym_1F_Text_JuanPostBattle, MSGBOX_DEFAULT
 * release
 * end
 * ```
 */
internal object SootopolisCity_Gym_1F_EventScript_Juan : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_JUAN_1: badge 8, TM03
    val giveTm: suspend () -> Unit = giveTm@{
      if (!ctx.giveItem(Items.TM03)) return@giveTm
      ctx.say(SootopolisCity_Gym_1F.ExplainWaterPulse)
      ctx.setFlag(HoennFlags.FLAG_RECEIVED_TM_WATER_PULSE)
    }
    if (ctx.isFlagSet(HoennFlags.FLAG_DEFEATED_SOOTOPOLIS_GYM)) {
      if (!ctx.isFlagSet(HoennFlags.FLAG_RECEIVED_TM_WATER_PULSE)) giveTm()
      else ctx.say(SootopolisCity_Gym_1F.JuanPostBattle)
      return
    }
    ctx.say(SootopolisCity_Gym_1F.JuanIntro)
    if (ctx.trainerBattle(272) != BattleResult.VICTORY) return
    ctx.say(SootopolisCity_Gym_1F.JuanDefeat)
    ctx.say(SootopolisCity_Gym_1F.ReceivedRainBadge)
    ctx.say(SootopolisCity_Gym_1F.ExplainRainBadgeTakeThis)
    ctx.setFlag(HoennFlags.FLAG_DEFEATED_SOOTOPOLIS_GYM)
    ctx.setFlag(HoennFlags.FLAG_BADGE08_GET)
    ctx.setFlag(HoennFlags.FLAG_HIDE_SOOTOPOLIS_CITY_RESIDENTS)
    ctx.setFlag(HoennFlags.FLAG_HIDE_SOOTOPOLIS_CITY_STEVEN)
    ctx.setFlag(HoennFlags.FLAG_HIDE_SOOTOPOLIS_CITY_WALLACE)
    ctx.setVar(HoennVars.VAR_SOOTOPOLIS_CITY_STATE, 6)
    ctx.clearFlag(HoennFlags.FLAG_HIDE_SOOTOPOLIS_CITY_MAN_1)
    giveTm()
    ctx.say(SootopolisCity_Gym_1F.RegisteredJuan)
    ctx.setFlag(HoennFlags.FLAG_ENABLE_JUAN_MATCH_CALL)
    ctx.badgeAwarded(8)
  }
}

internal val SootopolisCity_Gym_1FScripts: Map<String, Script> =
    mapOf(
        "SootopolisCity_Gym_1F_EventScript_Juan" to SootopolisCity_Gym_1F_EventScript_Juan,
    )
