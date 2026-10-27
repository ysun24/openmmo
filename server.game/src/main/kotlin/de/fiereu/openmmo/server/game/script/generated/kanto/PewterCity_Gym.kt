package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.PewterCity_Gym
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.battle.BattleResult
import de.fiereu.openmmo.server.game.progression.badgeAwarded
import de.fiereu.openmmo.server.game.progression.gymTrainer
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext
import de.fiereu.openmmo.story.generated.kanto.KantoFlags
import de.fiereu.openmmo.story.generated.kanto.KantoVars

/**
 * Not ported yet. Decomp body:
 * ```
 * famechecker FAMECHECKER_BROCK, FCPICKSTATE_COLORED, UpdatePickStateFromSpecialVar8005
 * trainerbattle_single TRAINER_LEADER_BROCK, PewterCity_Gym_Text_BrockIntro, PewterCity_Gym_Text_BrockDefeat, PewterCity_Gym_EventScript_DefeatedBrock, NO_MUSIC
 * goto_if_unset FLAG_GOT_TM39_FROM_BROCK, PewterCity_Gym_EventScript_GiveTM39
 * msgbox PewterCity_Gym_Text_BrockPostBattle
 * release
 * end
 * ```
 */
internal object PewterCity_Gym_EventScript_Brock : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_LEADER_BROCK: badge 1, TM39
    val giveTm: suspend () -> Unit = giveTm@{
      ctx.say(PewterCity_Gym.TakeThisWithYou)
      if (!ctx.giveItem(Items.TM39)) return@giveTm
      ctx.say(PewterCity_Gym.ReceivedTM39FromBrock)
      ctx.setFlag(KantoFlags.FLAG_GOT_TM39_FROM_BROCK)
      ctx.say(PewterCity_Gym.ExplainTM39)
    }
    if (ctx.isFlagSet(KantoFlags.FLAG_DEFEATED_BROCK)) {
      if (!ctx.isFlagSet(KantoFlags.FLAG_GOT_TM39_FROM_BROCK)) giveTm()
      else ctx.say(PewterCity_Gym.BrockPostBattle)
      return
    }
    ctx.say(PewterCity_Gym.BrockIntro)
    if (ctx.trainerBattle(414) != BattleResult.VICTORY) return
    ctx.setFlag(KantoFlags.FLAG_DEFEATED_BROCK)
    ctx.setFlag(KantoFlags.FLAG_BADGE01_GET)
    ctx.setVar(KantoVars.VAR_MAP_SCENE_PEWTER_CITY, 1)
    ctx.setFlag(KantoFlags.FLAG_HIDE_PEWTER_CITY_GYM_GUIDE)
    ctx.clearFlag(KantoFlags.FLAG_HIDE_PEWTER_CITY_RUNNING_SHOES_GUY)
    giveTm()
    ctx.badgeAwarded(1)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_CAMPER_LIAM, PewterCity_Gym_Text_LiamIntro, PewterCity_Gym_Text_LiamDefeat
 * msgbox PewterCity_Gym_Text_LiamPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object PewterCity_Gym_EventScript_Liam : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_CAMPER_LIAM
    ctx.gymTrainer(
        142, PewterCity_Gym.LiamIntro, PewterCity_Gym.LiamDefeat, PewterCity_Gym.LiamPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lock
 * faceplayer
 * goto_if_set FLAG_DEFEATED_BROCK, PewterCity_Gym_EventScript_GymGuyPostVictory
 * msgbox PewterCity_Gym_Text_LetMeTakeYouToTheTop, MSGBOX_YESNO
 * goto_if_eq VAR_RESULT, YES, PewterCity_Gym_EventScript_GymGuyTakeMeToTop
 * goto_if_eq VAR_RESULT, NO, PewterCity_Gym_EventScript_GymGuyDontTakeMeToTop
 * end
 * ```
 */
internal object PewterCity_Gym_EventScript_GymGuy : Script {
  override suspend fun run(ctx: ScriptContext) = TODO("port PewterCity_Gym_EventScript_GymGuy")
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lockall
 * goto_if_set FLAG_BADGE01_GET, PewterCity_Gym_EventScript_GymStatuePostVictory
 * msgbox PewterCity_Gym_Text_GymStatue
 * releaseall
 * end
 * ```
 */
internal object PewterCity_Gym_EventScript_GymStatue : Script {
  override suspend fun run(ctx: ScriptContext) = TODO("port PewterCity_Gym_EventScript_GymStatue")
}

internal val PewterCity_GymScripts: Map<String, Script> =
    mapOf(
        "PewterCity_Gym_EventScript_Brock" to PewterCity_Gym_EventScript_Brock,
        "PewterCity_Gym_EventScript_Liam" to PewterCity_Gym_EventScript_Liam,
        "PewterCity_Gym_EventScript_GymGuy" to PewterCity_Gym_EventScript_GymGuy,
        "PewterCity_Gym_EventScript_GymStatue" to PewterCity_Gym_EventScript_GymStatue,
    )
