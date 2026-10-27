package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.DewfordTown_Gym
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
 * trainerbattle_single TRAINER_BRAWLY_1, DewfordTown_Gym_Text_BrawlyIntro, DewfordTown_Gym_Text_BrawlyDefeat, DewfordTown_Gym_EventScript_BrawlyDefeated, NO_MUSIC
 * specialvar VAR_RESULT, ShouldTryRematchBattle
 * goto_if_eq VAR_RESULT, TRUE, DewfordTown_Gym_EventScript_BrawlyRematch
 * goto_if_unset FLAG_RECEIVED_TM_BULK_UP, DewfordTown_Gym_EventScript_GiveBulkUp2
 * msgbox DewfordTown_Gym_Text_BrawlyPostBattle, MSGBOX_DEFAULT
 * release
 * end
 * ```
 */
internal object DewfordTown_Gym_EventScript_Brawly : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_BRAWLY_1: badge 2, TM08
    val giveTm: suspend () -> Unit = giveTm@{
      if (!ctx.giveItem(Items.TM08)) return@giveTm
      ctx.say(DewfordTown_Gym.ExplainBulkUp)
      ctx.setFlag(HoennFlags.FLAG_RECEIVED_TM_BULK_UP)
    }
    if (ctx.isFlagSet(HoennFlags.FLAG_DEFEATED_DEWFORD_GYM)) {
      if (!ctx.isFlagSet(HoennFlags.FLAG_RECEIVED_TM_BULK_UP)) giveTm()
      else ctx.say(DewfordTown_Gym.BrawlyPostBattle)
      return
    }
    ctx.say(DewfordTown_Gym.BrawlyIntro)
    if (ctx.trainerBattle(266) != BattleResult.VICTORY) return
    ctx.say(DewfordTown_Gym.BrawlyDefeat)
    ctx.say(DewfordTown_Gym.ReceivedKnuckleBadge)
    ctx.say(DewfordTown_Gym.KnuckleBadgeInfoTakeThis)
    ctx.setFlag(HoennFlags.FLAG_DEFEATED_DEWFORD_GYM)
    ctx.setFlag(HoennFlags.FLAG_BADGE02_GET)
    ctx.setVar(HoennVars.VAR_PETALBURG_GYM_STATE, ctx.getVar(HoennVars.VAR_PETALBURG_GYM_STATE) + 1)
    giveTm()
    ctx.say(DewfordTown_Gym.RegisteredBrawly)
    ctx.setFlag(HoennFlags.FLAG_ENABLE_BRAWLY_MATCH_CALL)
    ctx.setVar(HoennVars.VAR_ROXANNE_CALL_STEP_COUNTER, 0)
    ctx.setFlag(HoennFlags.FLAG_ENABLE_ROXANNE_FIRST_CALL)
    ctx.badgeAwarded(2)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_TAKAO, DewfordTown_Gym_Text_TakaoIntro, DewfordTown_Gym_Text_TakaoDefeat, DewfordTown_Gym_EventScript_TakaoBrightenRoom
 * msgbox DewfordTown_Gym_Text_TakaoPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object DewfordTown_Gym_EventScript_Takao : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_TAKAO
    ctx.gymTrainer(
        179,
        DewfordTown_Gym.TakaoIntro,
        DewfordTown_Gym.TakaoDefeat,
        DewfordTown_Gym.TakaoPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_JOCELYN, DewfordTown_Gym_Text_JocelynIntro, DewfordTown_Gym_Text_JocelynDefeat, DewfordTown_Gym_EventScript_JocelynBrightenRoom
 * msgbox DewfordTown_Gym_Text_JocelynPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object DewfordTown_Gym_EventScript_Jocelyn : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_JOCELYN
    ctx.gymTrainer(
        425,
        DewfordTown_Gym.JocelynIntro,
        DewfordTown_Gym.JocelynDefeat,
        DewfordTown_Gym.JocelynPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_LAURA, DewfordTown_Gym_Text_LauraIntro, DewfordTown_Gym_Text_LauraDefeat, DewfordTown_Gym_EventScript_LauraBrightenRoom
 * msgbox DewfordTown_Gym_Text_LauraPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object DewfordTown_Gym_EventScript_Laura : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_LAURA
    ctx.gymTrainer(
        426,
        DewfordTown_Gym.LauraIntro,
        DewfordTown_Gym.LauraDefeat,
        DewfordTown_Gym.LauraPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lock
 * faceplayer
 * goto_if_set FLAG_DEFEATED_DEWFORD_GYM, DewfordTown_Gym_EventScript_GymGuidePostVictory
 * msgbox DewfordTown_Gym_Text_GymGuideAdvice, MSGBOX_DEFAULT
 * release
 * end
 * ```
 */
internal object DewfordTown_Gym_EventScript_GymGuide : Script {
  override suspend fun run(ctx: ScriptContext) = TODO("port DewfordTown_Gym_EventScript_GymGuide")
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_CRISTIAN, DewfordTown_Gym_Text_CristianIntro, DewfordTown_Gym_Text_CristianDefeat, DewfordTown_Gym_EventScript_CristianBrightenRoom
 * msgbox DewfordTown_Gym_Text_CristianPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object DewfordTown_Gym_EventScript_Cristian : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_CRISTIAN
    ctx.gymTrainer(
        574,
        DewfordTown_Gym.CristianIntro,
        DewfordTown_Gym.CristianDefeat,
        DewfordTown_Gym.CristianPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_LILITH, DewfordTown_Gym_Text_LilithIntro, DewfordTown_Gym_Text_LilithDefeat, DewfordTown_Gym_EventScript_LilithBrightenRoom
 * msgbox DewfordTown_Gym_Text_LilithPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object DewfordTown_Gym_EventScript_Lilith : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_LILITH
    ctx.gymTrainer(
        573,
        DewfordTown_Gym.LilithIntro,
        DewfordTown_Gym.LilithDefeat,
        DewfordTown_Gym.LilithPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_BRENDEN, DewfordTown_Gym_Text_BrendenIntro, DewfordTown_Gym_Text_BrendenDefeat, DewfordTown_Gym_EventScript_BrendenBrightenRoom
 * msgbox DewfordTown_Gym_Text_BrendenPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object DewfordTown_Gym_EventScript_Brenden : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_BRENDEN
    ctx.gymTrainer(
        572,
        DewfordTown_Gym.BrendenIntro,
        DewfordTown_Gym.BrendenDefeat,
        DewfordTown_Gym.BrendenPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lockall
 * goto_if_set FLAG_BADGE02_GET, DewfordTown_Gym_EventScript_GymStatueCertified
 * goto DewfordTown_Gym_EventScript_GymStatue
 * end
 * ```
 */
internal object DewfordTown_Gym_EventScript_LeftGymStatue : Script {
  override suspend fun run(ctx: ScriptContext) =
      TODO("port DewfordTown_Gym_EventScript_LeftGymStatue")
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lockall
 * goto_if_set FLAG_BADGE02_GET, DewfordTown_Gym_EventScript_GymStatueCertified
 * goto DewfordTown_Gym_EventScript_GymStatue
 * end
 * ```
 */
internal object DewfordTown_Gym_EventScript_RightGymStatue : Script {
  override suspend fun run(ctx: ScriptContext) =
      TODO("port DewfordTown_Gym_EventScript_RightGymStatue")
}

internal val DewfordTown_GymScripts: Map<String, Script> =
    mapOf(
        "DewfordTown_Gym_EventScript_Brawly" to DewfordTown_Gym_EventScript_Brawly,
        "DewfordTown_Gym_EventScript_Takao" to DewfordTown_Gym_EventScript_Takao,
        "DewfordTown_Gym_EventScript_Jocelyn" to DewfordTown_Gym_EventScript_Jocelyn,
        "DewfordTown_Gym_EventScript_Laura" to DewfordTown_Gym_EventScript_Laura,
        "DewfordTown_Gym_EventScript_GymGuide" to DewfordTown_Gym_EventScript_GymGuide,
        "DewfordTown_Gym_EventScript_Cristian" to DewfordTown_Gym_EventScript_Cristian,
        "DewfordTown_Gym_EventScript_Lilith" to DewfordTown_Gym_EventScript_Lilith,
        "DewfordTown_Gym_EventScript_Brenden" to DewfordTown_Gym_EventScript_Brenden,
        "DewfordTown_Gym_EventScript_LeftGymStatue" to DewfordTown_Gym_EventScript_LeftGymStatue,
        "DewfordTown_Gym_EventScript_RightGymStatue" to DewfordTown_Gym_EventScript_RightGymStatue,
    )
