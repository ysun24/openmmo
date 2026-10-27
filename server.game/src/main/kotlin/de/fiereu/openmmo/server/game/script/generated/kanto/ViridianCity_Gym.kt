package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.ViridianCity_Gym
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
 * trainerbattle_single TRAINER_BLACK_BELT_TAKASHI, ViridianCity_Gym_Text_TakashiIntro, ViridianCity_Gym_Text_TakashiDefeat
 * msgbox ViridianCity_Gym_Text_TakashiPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object ViridianCity_Gym_EventScript_Takashi : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_BLACK_BELT_TAKASHI
    ctx.gymTrainer(
        324,
        ViridianCity_Gym.TakashiIntro,
        ViridianCity_Gym.TakashiDefeat,
        ViridianCity_Gym.TakashiPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_COOLTRAINER_YUJI, ViridianCity_Gym_Text_YujiIntro, ViridianCity_Gym_Text_YujiDefeat
 * msgbox ViridianCity_Gym_Text_YujiPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object ViridianCity_Gym_EventScript_Yuji : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_COOLTRAINER_YUJI
    ctx.gymTrainer(
        400,
        ViridianCity_Gym.YujiIntro,
        ViridianCity_Gym.YujiDefeat,
        ViridianCity_Gym.YujiPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_BLACK_BELT_ATSUSHI, ViridianCity_Gym_Text_AtsushiIntro, ViridianCity_Gym_Text_AtsushiDefeat
 * msgbox ViridianCity_Gym_Text_AtsushiPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object ViridianCity_Gym_EventScript_Atsushi : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_BLACK_BELT_ATSUSHI
    ctx.gymTrainer(
        322,
        ViridianCity_Gym.AtsushiIntro,
        ViridianCity_Gym.AtsushiDefeat,
        ViridianCity_Gym.AtsushiPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_TAMER_JASON, ViridianCity_Gym_Text_JasonIntro, ViridianCity_Gym_Text_JasonDefeat
 * msgbox ViridianCity_Gym_Text_JasonPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object ViridianCity_Gym_EventScript_Jason : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_TAMER_JASON
    ctx.gymTrainer(
        296,
        ViridianCity_Gym.JasonIntro,
        ViridianCity_Gym.JasonDefeat,
        ViridianCity_Gym.JasonPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_TAMER_COLE, ViridianCity_Gym_Text_ColeIntro, ViridianCity_Gym_Text_ColeDefeat
 * msgbox ViridianCity_Gym_Text_ColePostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object ViridianCity_Gym_EventScript_Cole : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_TAMER_COLE
    ctx.gymTrainer(
        297,
        ViridianCity_Gym.ColeIntro,
        ViridianCity_Gym.ColeDefeat,
        ViridianCity_Gym.ColePostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_BLACK_BELT_KIYO, ViridianCity_Gym_Text_KiyoIntro, ViridianCity_Gym_Text_KiyoDefeat
 * msgbox ViridianCity_Gym_Text_KiyoPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object ViridianCity_Gym_EventScript_Kiyo : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_BLACK_BELT_KIYO
    ctx.gymTrainer(
        323,
        ViridianCity_Gym.KiyoIntro,
        ViridianCity_Gym.KiyoDefeat,
        ViridianCity_Gym.KiyoPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_COOLTRAINER_SAMUEL, ViridianCity_Gym_Text_SamuelIntro, ViridianCity_Gym_Text_SamuelDefeat
 * msgbox ViridianCity_Gym_Text_SamuelPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object ViridianCity_Gym_EventScript_Samuel : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_COOLTRAINER_SAMUEL
    ctx.gymTrainer(
        392,
        ViridianCity_Gym.SamuelIntro,
        ViridianCity_Gym.SamuelDefeat,
        ViridianCity_Gym.SamuelPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * famechecker FAMECHECKER_GIOVANNI, FCPICKSTATE_COLORED, UpdatePickStateFromSpecialVar8005
 * trainerbattle_single TRAINER_LEADER_GIOVANNI, ViridianCity_Gym_Text_GiovanniIntro, ViridianCity_Gym_Text_GiovanniDefeat, ViridianCity_Gym_EventScript_DefeatedGiovanni, NO_MUSIC
 * goto_if_unset FLAG_GOT_TM26_FROM_GIOVANNI, ViridianCity_Gym_EventScript_GiveTM26
 * msgbox ViridianCity_Gym_Text_GiovanniPostBattle
 * closemessage
 * fadescreen FADE_TO_BLACK
 * removeobject LOCALID_VIRIDIAN_GIOVANNI
 * fadescreen FADE_FROM_BLACK
 * release
 * end
 * ```
 */
internal object ViridianCity_Gym_EventScript_Giovanni : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_LEADER_GIOVANNI: badge 8, TM26
    val giveTm: suspend () -> Unit = giveTm@{
      ctx.say(ViridianCity_Gym.ExplainEarthBadgeTakeThis)
      if (!ctx.giveItem(Items.TM26)) return@giveTm
      ctx.say(ViridianCity_Gym.ReceivedTM26FromGiovanni)
      ctx.setFlag(KantoFlags.FLAG_GOT_TM26_FROM_GIOVANNI)
      ctx.say(ViridianCity_Gym.ExplainTM26)
    }
    if (ctx.isFlagSet(KantoFlags.FLAG_DEFEATED_LEADER_GIOVANNI)) {
      if (!ctx.isFlagSet(KantoFlags.FLAG_GOT_TM26_FROM_GIOVANNI)) giveTm()
      else ctx.say(ViridianCity_Gym.GiovanniPostBattle)
      return
    }
    ctx.say(ViridianCity_Gym.GiovanniIntro)
    if (ctx.trainerBattle(350) != BattleResult.VICTORY) return
    ctx.setFlag(KantoFlags.FLAG_HIDE_MISC_KANTO_ROCKETS)
    ctx.setFlag(KantoFlags.FLAG_DEFEATED_LEADER_GIOVANNI)
    ctx.setFlag(KantoFlags.FLAG_BADGE08_GET)
    ctx.setVar(KantoVars.VAR_MAP_SCENE_ROUTE22, 3)
    giveTm()
    ctx.badgeAwarded(8)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_COOLTRAINER_WARREN, ViridianCity_Gym_Text_WarrenIntro, ViridianCity_Gym_Text_WarrenDefeat
 * msgbox ViridianCity_Gym_Text_WarrenPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object ViridianCity_Gym_EventScript_Warren : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_COOLTRAINER_WARREN
    ctx.gymTrainer(
        401,
        ViridianCity_Gym.WarrenIntro,
        ViridianCity_Gym.WarrenDefeat,
        ViridianCity_Gym.WarrenPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lock
 * faceplayer
 * goto_if_set FLAG_DEFEATED_LEADER_GIOVANNI, ViridianCity_Gym_EventScript_GymGuyPostVictory
 * msgbox ViridianCity_Gym_Text_GymGuyAdvice
 * release
 * end
 * ```
 */
internal object ViridianCity_Gym_EventScript_GymGuy : Script {
  override suspend fun run(ctx: ScriptContext) = TODO("port ViridianCity_Gym_EventScript_GymGuy")
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lockall
 * goto_if_set FLAG_BADGE08_GET, ViridianCity_Gym_EventScript_GymStatuePostVictory
 * msgbox ViridianCity_Gym_Text_GymStatue
 * releaseall
 * end
 * ```
 */
internal object ViridianCity_Gym_EventScript_GymStatue : Script {
  override suspend fun run(ctx: ScriptContext) = TODO("port ViridianCity_Gym_EventScript_GymStatue")
}

internal val ViridianCity_GymScripts: Map<String, Script> =
    mapOf(
        "ViridianCity_Gym_EventScript_Takashi" to ViridianCity_Gym_EventScript_Takashi,
        "ViridianCity_Gym_EventScript_Yuji" to ViridianCity_Gym_EventScript_Yuji,
        "ViridianCity_Gym_EventScript_Atsushi" to ViridianCity_Gym_EventScript_Atsushi,
        "ViridianCity_Gym_EventScript_Jason" to ViridianCity_Gym_EventScript_Jason,
        "ViridianCity_Gym_EventScript_Cole" to ViridianCity_Gym_EventScript_Cole,
        "ViridianCity_Gym_EventScript_Kiyo" to ViridianCity_Gym_EventScript_Kiyo,
        "ViridianCity_Gym_EventScript_Samuel" to ViridianCity_Gym_EventScript_Samuel,
        "ViridianCity_Gym_EventScript_Giovanni" to ViridianCity_Gym_EventScript_Giovanni,
        "ViridianCity_Gym_EventScript_Warren" to ViridianCity_Gym_EventScript_Warren,
        "ViridianCity_Gym_EventScript_GymGuy" to ViridianCity_Gym_EventScript_GymGuy,
        "ViridianCity_Gym_EventScript_GymStatue" to ViridianCity_Gym_EventScript_GymStatue,
    )
