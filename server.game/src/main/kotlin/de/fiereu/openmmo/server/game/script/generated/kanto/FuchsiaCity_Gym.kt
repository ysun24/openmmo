package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.FuchsiaCity_Gym
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.battle.BattleResult
import de.fiereu.openmmo.server.game.progression.badgeAwarded
import de.fiereu.openmmo.server.game.progression.gymTrainer
import de.fiereu.openmmo.story.generated.kanto.KantoFlags
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_JUGGLER_KAYDEN, FuchsiaCity_Gym_Text_KaydenIntro, FuchsiaCity_Gym_Text_KaydenDefeat
 * msgbox FuchsiaCity_Gym_Text_KaydenPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object FuchsiaCity_Gym_EventScript_Kayden : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_JUGGLER_KAYDEN
    ctx.gymTrainer(292, FuchsiaCity_Gym.KaydenIntro, FuchsiaCity_Gym.KaydenDefeat, FuchsiaCity_Gym.KaydenPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_JUGGLER_SHAWN, FuchsiaCity_Gym_Text_ShawnIntro, FuchsiaCity_Gym_Text_ShawnDefeat
 * msgbox FuchsiaCity_Gym_Text_ShawnPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object FuchsiaCity_Gym_EventScript_Shawn : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_JUGGLER_SHAWN
    ctx.gymTrainer(289, FuchsiaCity_Gym.ShawnIntro, FuchsiaCity_Gym.ShawnDefeat, FuchsiaCity_Gym.ShawnPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_JUGGLER_KIRK, FuchsiaCity_Gym_Text_KirkIntro, FuchsiaCity_Gym_Text_KirkDefeat
 * famechecker FAMECHECKER_KOGA, 2
 * msgbox FuchsiaCity_Gym_Text_KirkPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object FuchsiaCity_Gym_EventScript_Kirk : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_JUGGLER_KIRK
    ctx.gymTrainer(288, FuchsiaCity_Gym.KirkIntro, FuchsiaCity_Gym.KirkDefeat, FuchsiaCity_Gym.KirkPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_TAMER_EDGAR, FuchsiaCity_Gym_Text_EdgarIntro, FuchsiaCity_Gym_Text_EdgarDefeat
 * msgbox FuchsiaCity_Gym_Text_EdgarPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object FuchsiaCity_Gym_EventScript_Edgar : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_TAMER_EDGAR
    ctx.gymTrainer(295, FuchsiaCity_Gym.EdgarIntro, FuchsiaCity_Gym.EdgarDefeat, FuchsiaCity_Gym.EdgarPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_TAMER_PHIL, FuchsiaCity_Gym_Text_PhilIntro, FuchsiaCity_Gym_Text_PhilDefeat
 * msgbox FuchsiaCity_Gym_Text_PhilPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object FuchsiaCity_Gym_EventScript_Phil : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_TAMER_PHIL
    ctx.gymTrainer(294, FuchsiaCity_Gym.PhilIntro, FuchsiaCity_Gym.PhilDefeat, FuchsiaCity_Gym.PhilPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_JUGGLER_NATE, FuchsiaCity_Gym_Text_NateIntro, FuchsiaCity_Gym_Text_NateDefeat
 * msgbox FuchsiaCity_Gym_Text_NatePostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object FuchsiaCity_Gym_EventScript_Nate : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_JUGGLER_NATE
    ctx.gymTrainer(293, FuchsiaCity_Gym.NateIntro, FuchsiaCity_Gym.NateDefeat, FuchsiaCity_Gym.NatePostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * famechecker FAMECHECKER_KOGA, FCPICKSTATE_COLORED, UpdatePickStateFromSpecialVar8005
 * trainerbattle_single TRAINER_LEADER_KOGA, FuchsiaCity_Gym_Text_KogaIntro, FuchsiaCity_Gym_Text_KogaDefeat, FuchsiaCity_Gym_EventScript_DefeatedKoga, NO_MUSIC
 * goto_if_unset FLAG_GOT_TM06_FROM_KOGA, FuchsiaCity_Gym_EventScript_GiveTM06
 * msgbox FuchsiaCity_Gym_Text_KogaPostBattle
 * release
 * end
 * ```
 */
internal object FuchsiaCity_Gym_EventScript_Koga : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_LEADER_KOGA: badge 5, TM06
    val giveTm: suspend () -> Unit = giveTm@{
      ctx.say(FuchsiaCity_Gym.KogaExplainSoulBadge)
      if (!ctx.giveItem(Items.TM06)) return@giveTm
      ctx.say(FuchsiaCity_Gym.ReceivedTM06FromKoga)
      ctx.setFlag(KantoFlags.FLAG_GOT_TM06_FROM_KOGA)
      ctx.say(FuchsiaCity_Gym.KogaExplainTM06)
    }
    if (ctx.isFlagSet(KantoFlags.FLAG_DEFEATED_KOGA)) {
      if (!ctx.isFlagSet(KantoFlags.FLAG_GOT_TM06_FROM_KOGA)) giveTm() else ctx.say(FuchsiaCity_Gym.KogaPostBattle)
      return
    }
    ctx.say(FuchsiaCity_Gym.KogaIntro)
    if (ctx.trainerBattle(418) != BattleResult.VICTORY) return
    ctx.say(FuchsiaCity_Gym.KogaDefeat)
    ctx.clearFlag(KantoFlags.FLAG_HIDE_FAME_CHECKER_KOGA_JOURNAL)
    ctx.setFlag(KantoFlags.FLAG_DEFEATED_KOGA)
    ctx.setFlag(KantoFlags.FLAG_BADGE05_GET)
    giveTm()
    ctx.badgeAwarded(5)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lock
 * faceplayer
 * goto_if_set FLAG_DEFEATED_KOGA, FuchsiaCity_Gym_EventScript_GymGuyPostVictory
 * msgbox FuchsiaCity_Gym_Text_GymGuyAdvice
 * release
 * end
 * ```
 */
internal object FuchsiaCity_Gym_EventScript_GymGuy : Script {
  override suspend fun run(ctx: ScriptContext) = TODO("port FuchsiaCity_Gym_EventScript_GymGuy")
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lockall
 * goto_if_set FLAG_BADGE05_GET, FuchsiaCity_Gym_EventScript_GymStatuePostVictory
 * msgbox FuchsiaCity_Gym_Text_GymStatue
 * releaseall
 * end
 * ```
 */
internal object FuchsiaCity_Gym_EventScript_GymStatue : Script {
  override suspend fun run(ctx: ScriptContext) = TODO("port FuchsiaCity_Gym_EventScript_GymStatue")
}

internal val FuchsiaCity_GymScripts: Map<String, Script> =
    mapOf(
        "FuchsiaCity_Gym_EventScript_Kayden" to FuchsiaCity_Gym_EventScript_Kayden,
        "FuchsiaCity_Gym_EventScript_Shawn" to FuchsiaCity_Gym_EventScript_Shawn,
        "FuchsiaCity_Gym_EventScript_Kirk" to FuchsiaCity_Gym_EventScript_Kirk,
        "FuchsiaCity_Gym_EventScript_Edgar" to FuchsiaCity_Gym_EventScript_Edgar,
        "FuchsiaCity_Gym_EventScript_Phil" to FuchsiaCity_Gym_EventScript_Phil,
        "FuchsiaCity_Gym_EventScript_Nate" to FuchsiaCity_Gym_EventScript_Nate,
        "FuchsiaCity_Gym_EventScript_Koga" to FuchsiaCity_Gym_EventScript_Koga,
        "FuchsiaCity_Gym_EventScript_GymGuy" to FuchsiaCity_Gym_EventScript_GymGuy,
        "FuchsiaCity_Gym_EventScript_GymStatue" to FuchsiaCity_Gym_EventScript_GymStatue,
    )
