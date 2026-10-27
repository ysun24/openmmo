package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.CeladonCity_Gym
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.battle.BattleResult
import de.fiereu.openmmo.server.game.progression.badgeAwarded
import de.fiereu.openmmo.server.game.progression.gymTrainer
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext
import de.fiereu.openmmo.story.generated.kanto.KantoFlags

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_LASS_KAY, CeladonCity_Gym_Text_KayIntro, CeladonCity_Gym_Text_KayDefeat
 * msgbox CeladonCity_Gym_Text_KayPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object CeladonCity_Gym_EventScript_Kay : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_LASS_KAY
    ctx.gymTrainer(
        132, CeladonCity_Gym.KayIntro, CeladonCity_Gym.KayDefeat, CeladonCity_Gym.KayPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_BEAUTY_BRIDGET, CeladonCity_Gym_Text_BridgetIntro, CeladonCity_Gym_Text_BridgetDefeat
 * msgbox CeladonCity_Gym_Text_BridgetPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object CeladonCity_Gym_EventScript_Bridget : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_BEAUTY_BRIDGET
    ctx.gymTrainer(
        265,
        CeladonCity_Gym.BridgetIntro,
        CeladonCity_Gym.BridgetDefeat,
        CeladonCity_Gym.BridgetPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_PICNICKER_TINA, CeladonCity_Gym_Text_TinaIntro, CeladonCity_Gym_Text_TinaDefeat
 * msgbox CeladonCity_Gym_Text_TinaPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object CeladonCity_Gym_EventScript_Tina : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_PICNICKER_TINA
    ctx.gymTrainer(
        160, CeladonCity_Gym.TinaIntro, CeladonCity_Gym.TinaDefeat, CeladonCity_Gym.TinaPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_BEAUTY_TAMIA, CeladonCity_Gym_Text_TamiaIntro, CeladonCity_Gym_Text_TamiaDefeat
 * famechecker FAMECHECKER_ERIKA, 3
 * msgbox CeladonCity_Gym_Text_TamiaPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object CeladonCity_Gym_EventScript_Tamia : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_BEAUTY_TAMIA
    ctx.gymTrainer(
        266,
        CeladonCity_Gym.TamiaIntro,
        CeladonCity_Gym.TamiaDefeat,
        CeladonCity_Gym.TamiaPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_BEAUTY_LORI, CeladonCity_Gym_Text_LoriIntro, CeladonCity_Gym_Text_LoriDefeat
 * msgbox CeladonCity_Gym_Text_LoriPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object CeladonCity_Gym_EventScript_Lori : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_BEAUTY_LORI
    ctx.gymTrainer(
        267, CeladonCity_Gym.LoriIntro, CeladonCity_Gym.LoriDefeat, CeladonCity_Gym.LoriPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_LASS_LISA, CeladonCity_Gym_Text_LisaIntro, CeladonCity_Gym_Text_LisaDefeat
 * famechecker FAMECHECKER_ERIKA, 2
 * msgbox CeladonCity_Gym_Text_LisaPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object CeladonCity_Gym_EventScript_Lisa : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_LASS_LISA
    ctx.gymTrainer(
        133, CeladonCity_Gym.LisaIntro, CeladonCity_Gym.LisaDefeat, CeladonCity_Gym.LisaPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * famechecker FAMECHECKER_ERIKA, FCPICKSTATE_COLORED, UpdatePickStateFromSpecialVar8005
 * trainerbattle_single TRAINER_LEADER_ERIKA, CeladonCity_Gym_Text_ErikaIntro, CeladonCity_Gym_Text_ErikaDefeat, CeladonCity_Gym_EventScript_DefeatedErika, NO_MUSIC
 * goto_if_unset FLAG_GOT_TM19_FROM_ERIKA, CeladonCity_Gym_EventScript_GiveTM19
 * famechecker FAMECHECKER_ERIKA, 4
 * msgbox CeladonCity_Gym_Text_ErikaPostBattle
 * release
 * end
 * ```
 */
internal object CeladonCity_Gym_EventScript_Erika : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_LEADER_ERIKA: badge 4, TM19
    val giveTm: suspend () -> Unit = giveTm@{
      ctx.say(CeladonCity_Gym.ExplainRainbowBadgeTakeThis)
      if (!ctx.giveItem(Items.TM19)) return@giveTm
      ctx.say(CeladonCity_Gym.ReceivedTM19FromErika)
      ctx.setFlag(KantoFlags.FLAG_GOT_TM19_FROM_ERIKA)
      ctx.say(CeladonCity_Gym.ExplainTM19)
    }
    if (ctx.isFlagSet(KantoFlags.FLAG_DEFEATED_ERIKA)) {
      if (!ctx.isFlagSet(KantoFlags.FLAG_GOT_TM19_FROM_ERIKA)) giveTm()
      else ctx.say(CeladonCity_Gym.ErikaPostBattle)
      return
    }
    if (ctx.trainerBattle(417) != BattleResult.VICTORY) return
    ctx.say(CeladonCity_Gym.ErikaDefeat)
    ctx.clearFlag(KantoFlags.FLAG_HIDE_FAME_CHECKER_ERIKA_JOURNALS)
    ctx.setFlag(KantoFlags.FLAG_DEFEATED_ERIKA)
    ctx.setFlag(KantoFlags.FLAG_BADGE04_GET)
    giveTm()
    ctx.badgeAwarded(4)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_COOLTRAINER_MARY, CeladonCity_Gym_Text_MaryIntro, CeladonCity_Gym_Text_MaryDefeat
 * msgbox CeladonCity_Gym_Text_MaryPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object CeladonCity_Gym_EventScript_Mary : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_COOLTRAINER_MARY
    ctx.gymTrainer(
        402, CeladonCity_Gym.MaryIntro, CeladonCity_Gym.MaryDefeat, CeladonCity_Gym.MaryPostBattle)
  }
}

internal val CeladonCity_GymScripts: Map<String, Script> =
    mapOf(
        "CeladonCity_Gym_EventScript_Kay" to CeladonCity_Gym_EventScript_Kay,
        "CeladonCity_Gym_EventScript_Bridget" to CeladonCity_Gym_EventScript_Bridget,
        "CeladonCity_Gym_EventScript_Tina" to CeladonCity_Gym_EventScript_Tina,
        "CeladonCity_Gym_EventScript_Tamia" to CeladonCity_Gym_EventScript_Tamia,
        "CeladonCity_Gym_EventScript_Lori" to CeladonCity_Gym_EventScript_Lori,
        "CeladonCity_Gym_EventScript_Lisa" to CeladonCity_Gym_EventScript_Lisa,
        "CeladonCity_Gym_EventScript_Erika" to CeladonCity_Gym_EventScript_Erika,
        "CeladonCity_Gym_EventScript_Mary" to CeladonCity_Gym_EventScript_Mary,
    )
