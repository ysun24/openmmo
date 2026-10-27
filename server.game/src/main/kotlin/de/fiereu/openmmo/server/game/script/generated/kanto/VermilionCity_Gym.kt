package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.VermilionCity_Gym
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
 * famechecker FAMECHECKER_LTSURGE, FCPICKSTATE_COLORED, UpdatePickStateFromSpecialVar8005
 * trainerbattle_single TRAINER_LEADER_LT_SURGE, VermilionCity_Gym_Text_LtSurgeIntro, VermilionCity_Gym_Text_LtSurgeDefeat, VermilionCity_Gym_EventScript_DefeatedLtSurge, NO_MUSIC
 * goto_if_unset FLAG_GOT_TM34_FROM_SURGE, VermilionCity_Gym_EventScript_GiveTM34
 * msgbox VermilionCity_Gym_Text_LtSurgePostBattle
 * release
 * end
 * ```
 */
internal object VermilionCity_Gym_EventScript_LtSurge : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_LEADER_LT_SURGE: badge 3, TM34
    val giveTm: suspend () -> Unit = giveTm@{
      ctx.say(VermilionCity_Gym.ExplainThunderBadgeTakeThis)
      if (!ctx.giveItem(Items.TM34)) return@giveTm
      ctx.say(VermilionCity_Gym.ReceivedTM34FromLtSurge)
      ctx.setFlag(KantoFlags.FLAG_GOT_TM34_FROM_SURGE)
      ctx.say(VermilionCity_Gym.ExplainTM34)
    }
    if (ctx.isFlagSet(KantoFlags.FLAG_DEFEATED_LT_SURGE)) {
      if (!ctx.isFlagSet(KantoFlags.FLAG_GOT_TM34_FROM_SURGE)) giveTm()
      else ctx.say(VermilionCity_Gym.LtSurgePostBattle)
      return
    }
    ctx.say(VermilionCity_Gym.LtSurgeIntro)
    if (ctx.trainerBattle(416) != BattleResult.VICTORY) return
    ctx.say(VermilionCity_Gym.LtSurgeDefeat)
    ctx.clearFlag(KantoFlags.FLAG_HIDE_FAME_CHECKER_LT_SURGE_JOURNAL)
    ctx.setFlag(KantoFlags.FLAG_DEFEATED_LT_SURGE)
    ctx.setFlag(KantoFlags.FLAG_BADGE03_GET)
    giveTm()
    ctx.badgeAwarded(3)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_ENGINEER_BAILY, VermilionCity_Gym_Text_BailyIntro, VermilionCity_Gym_Text_BailyDefeat
 * msgbox VermilionCity_Gym_Text_BailyPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object VermilionCity_Gym_EventScript_Baily : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_ENGINEER_BAILY
    ctx.gymTrainer(
        220,
        VermilionCity_Gym.BailyIntro,
        VermilionCity_Gym.BailyDefeat,
        VermilionCity_Gym.BailyPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_SAILOR_DWAYNE, VermilionCity_Gym_Text_DwayneIntro, VermilionCity_Gym_Text_DwayneDefeat
 * famechecker FAMECHECKER_LTSURGE, 4
 * msgbox VermilionCity_Gym_Text_DwaynePostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object VermilionCity_Gym_EventScript_Dwayne : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_SAILOR_DWAYNE
    ctx.gymTrainer(
        141,
        VermilionCity_Gym.DwayneIntro,
        VermilionCity_Gym.DwayneDefeat,
        VermilionCity_Gym.DwaynePostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_GENTLEMAN_TUCKER, VermilionCity_Gym_Text_TuckerIntro, VermilionCity_Gym_Text_TuckerDefeat, VermilionCity_Gym_EventScript_DefeatedTucker
 * famechecker FAMECHECKER_LTSURGE, 3
 * msgbox VermilionCity_Gym_Text_TuckerPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object VermilionCity_Gym_EventScript_Tucker : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_GENTLEMAN_TUCKER
    ctx.gymTrainer(
        423,
        VermilionCity_Gym.TuckerIntro,
        VermilionCity_Gym.TuckerDefeat,
        VermilionCity_Gym.TuckerPostBattle)
  }
}

internal val VermilionCity_GymScripts: Map<String, Script> =
    mapOf(
        "VermilionCity_Gym_EventScript_LtSurge" to VermilionCity_Gym_EventScript_LtSurge,
        "VermilionCity_Gym_EventScript_Baily" to VermilionCity_Gym_EventScript_Baily,
        "VermilionCity_Gym_EventScript_Dwayne" to VermilionCity_Gym_EventScript_Dwayne,
        "VermilionCity_Gym_EventScript_Tucker" to VermilionCity_Gym_EventScript_Tucker,
    )
