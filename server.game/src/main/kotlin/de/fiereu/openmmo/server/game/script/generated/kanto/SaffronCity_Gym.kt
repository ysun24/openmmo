package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.SaffronCity_Gym
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
 * trainerbattle_single TRAINER_PSYCHIC_CAMERON, SaffronCity_Gym_Text_CameronIntro, SaffronCity_Gym_Text_CameronDefeat
 * msgbox SaffronCity_Gym_Text_CameronPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object SaffronCity_Gym_EventScript_Cameron : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_PSYCHIC_CAMERON
    ctx.gymTrainer(
        282,
        SaffronCity_Gym.CameronIntro,
        SaffronCity_Gym.CameronDefeat,
        SaffronCity_Gym.CameronPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_PSYCHIC_JOHAN, SaffronCity_Gym_Text_JohanIntro, SaffronCity_Gym_Text_JohanDefeat
 * msgbox SaffronCity_Gym_Text_JohanPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object SaffronCity_Gym_EventScript_Johan : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_PSYCHIC_JOHAN
    ctx.gymTrainer(
        280,
        SaffronCity_Gym.JohanIntro,
        SaffronCity_Gym.JohanDefeat,
        SaffronCity_Gym.JohanPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_PSYCHIC_PRESTON, SaffronCity_Gym_Text_PrestonIntro, SaffronCity_Gym_Text_PrestonDefeat
 * msgbox SaffronCity_Gym_Text_PrestonPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object SaffronCity_Gym_EventScript_Preston : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_PSYCHIC_PRESTON
    ctx.gymTrainer(
        283,
        SaffronCity_Gym.PrestonIntro,
        SaffronCity_Gym.PrestonDefeat,
        SaffronCity_Gym.PrestonPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_CHANNELER_AMANDA, SaffronCity_Gym_Text_AmandaIntro, SaffronCity_Gym_Text_AmandaDefeat
 * msgbox SaffronCity_Gym_Text_AmandaPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object SaffronCity_Gym_EventScript_Amanda : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_CHANNELER_AMANDA
    ctx.gymTrainer(
        462,
        SaffronCity_Gym.AmandaIntro,
        SaffronCity_Gym.AmandaDefeat,
        SaffronCity_Gym.AmandaPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_CHANNELER_STACY, SaffronCity_Gym_Text_StacyIntro, SaffronCity_Gym_Text_StacyDefeat
 * msgbox SaffronCity_Gym_Text_StacyPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object SaffronCity_Gym_EventScript_Stacy : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_CHANNELER_STACY
    ctx.gymTrainer(
        463,
        SaffronCity_Gym.StacyIntro,
        SaffronCity_Gym.StacyDefeat,
        SaffronCity_Gym.StacyPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_CHANNELER_TASHA, SaffronCity_Gym_Text_TashaIntro, SaffronCity_Gym_Text_TashaDefeat
 * msgbox SaffronCity_Gym_Text_TashaPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object SaffronCity_Gym_EventScript_Tasha : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_CHANNELER_TASHA
    ctx.gymTrainer(
        464,
        SaffronCity_Gym.TashaIntro,
        SaffronCity_Gym.TashaDefeat,
        SaffronCity_Gym.TashaPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * famechecker FAMECHECKER_SABRINA, FCPICKSTATE_COLORED, UpdatePickStateFromSpecialVar8005
 * trainerbattle_single TRAINER_LEADER_SABRINA, SaffronCity_Gym_Text_SabrinaIntro, SaffronCity_Gym_Text_SabrinaDefeat, SaffronCity_Gym_EventScript_DefeatedSabrina, NO_MUSIC
 * goto_if_unset FLAG_GOT_TM04_FROM_SABRINA, SaffronCity_Gym_EventScript_GiveTM04
 * msgbox SaffronCity_Gym_Text_ExplainTM04
 * release
 * end
 * ```
 */
internal object SaffronCity_Gym_EventScript_Sabrina : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_LEADER_SABRINA: badge 6, TM04
    val giveTm: suspend () -> Unit = giveTm@{
      ctx.say(SaffronCity_Gym.ExplainMarshBadgeTakeThis)
      if (!ctx.giveItem(Items.TM04)) return@giveTm
      ctx.say(SaffronCity_Gym.ReceivedTM04FromSabrina)
      ctx.setFlag(KantoFlags.FLAG_GOT_TM04_FROM_SABRINA)
      ctx.say(SaffronCity_Gym.SabrinaPostBattle)
    }
    if (ctx.isFlagSet(KantoFlags.FLAG_DEFEATED_SABRINA)) {
      if (!ctx.isFlagSet(KantoFlags.FLAG_GOT_TM04_FROM_SABRINA)) giveTm()
      else ctx.say(SaffronCity_Gym.ExplainTM04)
      return
    }
    ctx.say(SaffronCity_Gym.SabrinaIntro)
    if (ctx.trainerBattle(420) != BattleResult.VICTORY) return
    ctx.say(SaffronCity_Gym.SabrinaDefeat)
    ctx.clearFlag(KantoFlags.FLAG_HIDE_SAFFRON_CITY_POKECENTER_SABRINA_JOURNALS)
    ctx.setFlag(KantoFlags.FLAG_DEFEATED_SABRINA)
    ctx.setFlag(KantoFlags.FLAG_BADGE06_GET)
    giveTm()
    ctx.badgeAwarded(6)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_PSYCHIC_TYRON, SaffronCity_Gym_Text_TyronIntro, SaffronCity_Gym_Text_TyronDefeat
 * famechecker FAMECHECKER_SABRINA, 5
 * msgbox SaffronCity_Gym_Text_TyronPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object SaffronCity_Gym_EventScript_Tyron : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_PSYCHIC_TYRON
    ctx.gymTrainer(
        281,
        SaffronCity_Gym.TyronIntro,
        SaffronCity_Gym.TyronDefeat,
        SaffronCity_Gym.TyronPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lock
 * faceplayer
 * goto_if_set FLAG_DEFEATED_SABRINA, SaffronCity_Gym_EventScript_GymGuyPostVictory
 * msgbox SaffronCity_Gym_Text_GymGuyAdvice
 * release
 * end
 * ```
 */
internal object SaffronCity_Gym_EventScript_GymGuy : Script {
  override suspend fun run(ctx: ScriptContext) = TODO("port SaffronCity_Gym_EventScript_GymGuy")
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lockall
 * goto_if_set FLAG_BADGE06_GET, SaffronCity_Gym_EventScript_GymStatuePostVictory
 * msgbox SaffronCity_Gym_Text_GymStatue
 * releaseall
 * end
 * ```
 */
internal object SaffronCity_Gym_EventScript_GymStatue : Script {
  override suspend fun run(ctx: ScriptContext) = TODO("port SaffronCity_Gym_EventScript_GymStatue")
}

internal val SaffronCity_GymScripts: Map<String, Script> =
    mapOf(
        "SaffronCity_Gym_EventScript_Cameron" to SaffronCity_Gym_EventScript_Cameron,
        "SaffronCity_Gym_EventScript_Johan" to SaffronCity_Gym_EventScript_Johan,
        "SaffronCity_Gym_EventScript_Preston" to SaffronCity_Gym_EventScript_Preston,
        "SaffronCity_Gym_EventScript_Amanda" to SaffronCity_Gym_EventScript_Amanda,
        "SaffronCity_Gym_EventScript_Stacy" to SaffronCity_Gym_EventScript_Stacy,
        "SaffronCity_Gym_EventScript_Tasha" to SaffronCity_Gym_EventScript_Tasha,
        "SaffronCity_Gym_EventScript_Sabrina" to SaffronCity_Gym_EventScript_Sabrina,
        "SaffronCity_Gym_EventScript_Tyron" to SaffronCity_Gym_EventScript_Tyron,
        "SaffronCity_Gym_EventScript_GymGuy" to SaffronCity_Gym_EventScript_GymGuy,
        "SaffronCity_Gym_EventScript_GymStatue" to SaffronCity_Gym_EventScript_GymStatue,
    )
