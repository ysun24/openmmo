package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.LavaridgeTown_Gym_1F
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
 * trainerbattle_single TRAINER_FLANNERY_1, LavaridgeTown_Gym_1F_Text_FlanneryIntro, LavaridgeTown_Gym_1F_Text_FlanneryDefeat, LavaridgeTown_Gym_1F_EventScript_FlanneryDefeated, NO_MUSIC
 * specialvar VAR_RESULT, ShouldTryRematchBattle
 * goto_if_eq VAR_RESULT, TRUE, LavaridgeTown_Gym_1F_EventScript_FlanneryRematch
 * goto_if_unset FLAG_RECEIVED_TM_OVERHEAT, LavaridgeTown_Gym_1F_EventScript_GiveOverheat2
 * msgbox LavaridgeTown_Gym_1F_Text_FlanneryPostBattle, MSGBOX_DEFAULT
 * release
 * end
 * ```
 */
internal object LavaridgeTown_Gym_1F_EventScript_Flannery : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_FLANNERY_1: badge 4, TM50
    val giveTm: suspend () -> Unit = giveTm@{
      if (!ctx.giveItem(Items.TM50)) return@giveTm
      ctx.say(LavaridgeTown_Gym_1F.ExplainOverheat)
      ctx.setFlag(HoennFlags.FLAG_RECEIVED_TM_OVERHEAT)
    }
    if (ctx.isFlagSet(HoennFlags.FLAG_DEFEATED_LAVARIDGE_GYM)) {
      if (!ctx.isFlagSet(HoennFlags.FLAG_RECEIVED_TM_OVERHEAT)) giveTm()
      else ctx.say(LavaridgeTown_Gym_1F.FlanneryPostBattle)
      return
    }
    ctx.say(LavaridgeTown_Gym_1F.FlanneryIntro)
    if (ctx.trainerBattle(268) != BattleResult.VICTORY) return
    ctx.say(LavaridgeTown_Gym_1F.FlanneryDefeat)
    ctx.say(LavaridgeTown_Gym_1F.ReceivedHeatBadge)
    ctx.say(LavaridgeTown_Gym_1F.ExplainHeatBadgeTakeThis)
    ctx.setFlag(HoennFlags.FLAG_WHITEOUT_TO_LAVARIDGE)
    ctx.setFlag(HoennFlags.FLAG_DEFEATED_LAVARIDGE_GYM)
    ctx.setFlag(HoennFlags.FLAG_BADGE04_GET)
    ctx.setVar(HoennVars.VAR_PETALBURG_GYM_STATE, ctx.getVar(HoennVars.VAR_PETALBURG_GYM_STATE) + 1)
    ctx.setFlag(HoennFlags.FLAG_HIDE_VERDANTURF_TOWN_WANDAS_HOUSE_WALLY)
    ctx.setVar(HoennVars.VAR_LAVARIDGE_TOWN_STATE, 1)
    giveTm()
    ctx.say(LavaridgeTown_Gym_1F.RegisteredFlannery)
    ctx.setFlag(HoennFlags.FLAG_ENABLE_FLANNERY_MATCH_CALL)
    ctx.badgeAwarded(4)
  }
}

internal val LavaridgeTown_Gym_1FScripts: Map<String, Script> =
    mapOf(
        "LavaridgeTown_Gym_1F_EventScript_Flannery" to LavaridgeTown_Gym_1F_EventScript_Flannery,
    )
