package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.MossdeepCity_Gym
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.battle.BattleResult
import de.fiereu.openmmo.server.game.progression.badgeAwarded
import de.fiereu.openmmo.server.game.progression.gymTrainer
import de.fiereu.openmmo.story.generated.hoenn.HoennFlags
import de.fiereu.openmmo.story.generated.hoenn.HoennVars
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_double TRAINER_TATE_AND_LIZA_1, MossdeepCity_Gym_Text_TateAndLizaIntro, MossdeepCity_Gym_Text_TateAndLizaDefeat, MossdeepCity_Gym_Text_TateAndLizaNeedTwoMons, MossdeepCity_Gym_EventScript_TateAndLizaDefeated, NO_MUSIC
 * specialvar VAR_RESULT, ShouldTryRematchBattle
 * goto_if_eq VAR_RESULT, TRUE, MossdeepCity_Gym_EventScript_TateAndLizaRematch
 * goto_if_unset FLAG_RECEIVED_TM_CALM_MIND, MossdeepCity_Gym_EventScript_GiveCalmMind2
 * msgbox MossdeepCity_Gym_Text_TateAndLizaPostBattle, MSGBOX_DEFAULT
 * release
 * end
 * ```
 */
internal object MossdeepCity_Gym_EventScript_TateAndLiza : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_TATE_AND_LIZA_1: badge 7, TM04
    val giveTm: suspend () -> Unit = giveTm@{
      if (!ctx.giveItem(Items.TM04)) return@giveTm
      ctx.say(MossdeepCity_Gym.ExplainCalmMind)
      ctx.setFlag(HoennFlags.FLAG_RECEIVED_TM_CALM_MIND)
    }
    if (ctx.isFlagSet(HoennFlags.FLAG_DEFEATED_MOSSDEEP_GYM)) {
      if (!ctx.isFlagSet(HoennFlags.FLAG_RECEIVED_TM_CALM_MIND)) giveTm() else ctx.say(MossdeepCity_Gym.TateAndLizaPostBattle)
      return
    }
    ctx.say(MossdeepCity_Gym.TateAndLizaIntro)
    if (ctx.trainerBattle(271) != BattleResult.VICTORY) return
    ctx.say(MossdeepCity_Gym.TateAndLizaDefeat)
    ctx.say(MossdeepCity_Gym.ReceivedMindBadge)
    ctx.say(MossdeepCity_Gym.ExplainMindBadgeTakeThis)
    ctx.setFlag(HoennFlags.FLAG_DEFEATED_MOSSDEEP_GYM)
    ctx.setFlag(HoennFlags.FLAG_BADGE07_GET)
    ctx.setFlag(HoennFlags.FLAG_HIDE_AQUA_HIDEOUT_GRUNTS)
    ctx.clearFlag(HoennFlags.FLAG_HIDE_SLATEPORT_CITY_STERNS_SHIPYARD_MR_BRINEY)
    ctx.clearFlag(HoennFlags.FLAG_UNKNOWN_0x393)
    ctx.clearFlag(HoennFlags.FLAG_HIDE_SLATEPORT_CITY_HARBOR_PATRONS)
    ctx.clearFlag(HoennFlags.FLAG_HIDE_MOSSDEEP_CITY_TEAM_MAGMA)
    ctx.clearFlag(HoennFlags.FLAG_HIDE_MOSSDEEP_CITY_SPACE_CENTER_1F_TEAM_MAGMA)
    ctx.clearFlag(HoennFlags.FLAG_HIDE_MOSSDEEP_CITY_SPACE_CENTER_2F_TEAM_MAGMA)
    ctx.clearFlag(HoennFlags.FLAG_HIDE_MOSSDEEP_CITY_SPACE_CENTER_2F_STEVEN)
    ctx.setVar(HoennVars.VAR_MOSSDEEP_CITY_STATE, 1)
    ctx.setFlag(HoennFlags.FLAG_HIDE_MOSSDEEP_CITY_SPACE_CENTER_1F_STEVEN)
    ctx.setVar(HoennVars.VAR_MOSSDEEP_SPACE_CENTER_STATE, 1)
    giveTm()
    ctx.say(MossdeepCity_Gym.RegisteredTateAndLiza)
    ctx.setFlag(HoennFlags.FLAG_ENABLE_TATE_AND_LIZA_MATCH_CALL)
    ctx.badgeAwarded(7)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_PRESTON, MossdeepCity_Gym_Text_PrestonIntro, MossdeepCity_Gym_Text_PrestonDefeat
 * msgbox MossdeepCity_Gym_Text_PrestonPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object MossdeepCity_Gym_EventScript_Preston : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_PRESTON
    ctx.gymTrainer(233, MossdeepCity_Gym.PrestonIntro, MossdeepCity_Gym.PrestonDefeat, MossdeepCity_Gym.PrestonPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_BLAKE, MossdeepCity_Gym_Text_BlakeIntro, MossdeepCity_Gym_Text_BlakeDefeat
 * msgbox MossdeepCity_Gym_Text_BlakePostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object MossdeepCity_Gym_EventScript_Blake : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_BLAKE
    ctx.gymTrainer(235, MossdeepCity_Gym.BlakeIntro, MossdeepCity_Gym.BlakeDefeat, MossdeepCity_Gym.BlakePostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_MAURA, MossdeepCity_Gym_Text_MauraIntro, MossdeepCity_Gym_Text_MauraDefeat
 * msgbox MossdeepCity_Gym_Text_MauraPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object MossdeepCity_Gym_EventScript_Maura : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_MAURA
    ctx.gymTrainer(246, MossdeepCity_Gym.MauraIntro, MossdeepCity_Gym.MauraDefeat, MossdeepCity_Gym.MauraPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_SAMANTHA, MossdeepCity_Gym_Text_SamanthaIntro, MossdeepCity_Gym_Text_SamanthaDefeat
 * msgbox MossdeepCity_Gym_Text_SamanthaPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object MossdeepCity_Gym_EventScript_Samantha : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_SAMANTHA
    ctx.gymTrainer(245, MossdeepCity_Gym.SamanthaIntro, MossdeepCity_Gym.SamanthaDefeat, MossdeepCity_Gym.SamanthaPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_VIRGIL, MossdeepCity_Gym_Text_VirgilIntro, MossdeepCity_Gym_Text_VirgilDefeat
 * msgbox MossdeepCity_Gym_Text_VirgilPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object MossdeepCity_Gym_EventScript_Virgil : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_VIRGIL
    ctx.gymTrainer(234, MossdeepCity_Gym.VirgilIntro, MossdeepCity_Gym.VirgilDefeat, MossdeepCity_Gym.VirgilPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_HANNAH, MossdeepCity_Gym_Text_HannahIntro, MossdeepCity_Gym_Text_HannahDefeat
 * msgbox MossdeepCity_Gym_Text_HannahPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object MossdeepCity_Gym_EventScript_Hannah : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_HANNAH
    ctx.gymTrainer(244, MossdeepCity_Gym.HannahIntro, MossdeepCity_Gym.HannahDefeat, MossdeepCity_Gym.HannahPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lock
 * faceplayer
 * goto_if_set FLAG_DEFEATED_MOSSDEEP_GYM, MossdeepCity_Gym_EventScript_GymGuidePostVictory
 * msgbox MossdeepCity_Gym_Text_GymGuideAdvice, MSGBOX_DEFAULT
 * release
 * end
 * ```
 */
internal object MossdeepCity_Gym_EventScript_GymGuide : Script {
  override suspend fun run(ctx: ScriptContext) = TODO("port MossdeepCity_Gym_EventScript_GymGuide")
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_NATE, MossdeepCity_Gym_Text_NateIntro, MossdeepCity_Gym_Text_NateDefeat
 * msgbox MossdeepCity_Gym_Text_NatePostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object MossdeepCity_Gym_EventScript_Nate : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_NATE
    ctx.gymTrainer(582, MossdeepCity_Gym.NateIntro, MossdeepCity_Gym.NateDefeat, MossdeepCity_Gym.NatePostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_SYLVIA, MossdeepCity_Gym_Text_SylviaIntro, MossdeepCity_Gym_Text_SylviaDefeat
 * msgbox MossdeepCity_Gym_Text_SylviaPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object MossdeepCity_Gym_EventScript_Sylvia : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_SYLVIA
    ctx.gymTrainer(575, MossdeepCity_Gym.SylviaIntro, MossdeepCity_Gym.SylviaDefeat, MossdeepCity_Gym.SylviaPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_CLIFFORD, MossdeepCity_Gym_Text_CliffordIntro, MossdeepCity_Gym_Text_CliffordDefeat
 * msgbox MossdeepCity_Gym_Text_CliffordPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object MossdeepCity_Gym_EventScript_Clifford : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_CLIFFORD
    ctx.gymTrainer(584, MossdeepCity_Gym.CliffordIntro, MossdeepCity_Gym.CliffordDefeat, MossdeepCity_Gym.CliffordPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_MACEY, MossdeepCity_Gym_Text_MaceyIntro, MossdeepCity_Gym_Text_MaceyDefeat
 * msgbox MossdeepCity_Gym_Text_MaceyPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object MossdeepCity_Gym_EventScript_Macey : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_MACEY
    ctx.gymTrainer(591, MossdeepCity_Gym.MaceyIntro, MossdeepCity_Gym.MaceyDefeat, MossdeepCity_Gym.MaceyPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_KATHLEEN, MossdeepCity_Gym_Text_KathleenIntro, MossdeepCity_Gym_Text_KathleenDefeat
 * msgbox MossdeepCity_Gym_Text_KathleenPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object MossdeepCity_Gym_EventScript_Kathleen : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_KATHLEEN
    ctx.gymTrainer(583, MossdeepCity_Gym.KathleenIntro, MossdeepCity_Gym.KathleenDefeat, MossdeepCity_Gym.KathleenPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_NICHOLAS, MossdeepCity_Gym_Text_NicholasIntro, MossdeepCity_Gym_Text_NicholasDefeat
 * msgbox MossdeepCity_Gym_Text_NicholasPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object MossdeepCity_Gym_EventScript_Nicholas : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_NICHOLAS
    ctx.gymTrainer(585, MossdeepCity_Gym.NicholasIntro, MossdeepCity_Gym.NicholasDefeat, MossdeepCity_Gym.NicholasPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lockall
 * goto_if_set FLAG_BADGE07_GET, MossdeepCity_Gym_EventScript_GymStatueCertified
 * goto MossdeepCity_Gym_EventScript_GymStatue
 * end
 * ```
 */
internal object MossdeepCity_Gym_EventScript_LeftGymStatue : Script {
  override suspend fun run(ctx: ScriptContext) =
      TODO("port MossdeepCity_Gym_EventScript_LeftGymStatue")
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lockall
 * goto_if_set FLAG_BADGE07_GET, MossdeepCity_Gym_EventScript_GymStatueCertified
 * goto MossdeepCity_Gym_EventScript_GymStatue
 * end
 * ```
 */
internal object MossdeepCity_Gym_EventScript_RightGymStatue : Script {
  override suspend fun run(ctx: ScriptContext) =
      TODO("port MossdeepCity_Gym_EventScript_RightGymStatue")
}

internal val MossdeepCity_GymScripts: Map<String, Script> =
    mapOf(
        "MossdeepCity_Gym_EventScript_TateAndLiza" to MossdeepCity_Gym_EventScript_TateAndLiza,
        "MossdeepCity_Gym_EventScript_Preston" to MossdeepCity_Gym_EventScript_Preston,
        "MossdeepCity_Gym_EventScript_Blake" to MossdeepCity_Gym_EventScript_Blake,
        "MossdeepCity_Gym_EventScript_Maura" to MossdeepCity_Gym_EventScript_Maura,
        "MossdeepCity_Gym_EventScript_Samantha" to MossdeepCity_Gym_EventScript_Samantha,
        "MossdeepCity_Gym_EventScript_Virgil" to MossdeepCity_Gym_EventScript_Virgil,
        "MossdeepCity_Gym_EventScript_Hannah" to MossdeepCity_Gym_EventScript_Hannah,
        "MossdeepCity_Gym_EventScript_GymGuide" to MossdeepCity_Gym_EventScript_GymGuide,
        "MossdeepCity_Gym_EventScript_Nate" to MossdeepCity_Gym_EventScript_Nate,
        "MossdeepCity_Gym_EventScript_Sylvia" to MossdeepCity_Gym_EventScript_Sylvia,
        "MossdeepCity_Gym_EventScript_Clifford" to MossdeepCity_Gym_EventScript_Clifford,
        "MossdeepCity_Gym_EventScript_Macey" to MossdeepCity_Gym_EventScript_Macey,
        "MossdeepCity_Gym_EventScript_Kathleen" to MossdeepCity_Gym_EventScript_Kathleen,
        "MossdeepCity_Gym_EventScript_Nicholas" to MossdeepCity_Gym_EventScript_Nicholas,
        "MossdeepCity_Gym_EventScript_LeftGymStatue" to MossdeepCity_Gym_EventScript_LeftGymStatue,
        "MossdeepCity_Gym_EventScript_RightGymStatue" to
            MossdeepCity_Gym_EventScript_RightGymStatue,
    )
