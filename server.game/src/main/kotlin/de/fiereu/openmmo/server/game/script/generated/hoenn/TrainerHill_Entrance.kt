package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Misc
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext
import de.fiereu.openmmo.story.generated.hoenn.HoennFlags

/**
 * Not ported yet. Decomp body:
 * ```
 * lock
 * faceplayer
 * trainerhill_inchallenge
 * goto_if_eq VAR_RESULT, FALSE, TrainerHill_Entrance_EventScript_ThanksForPlaying
 * msgbox TrainerHill_Entrance_Text_HopeYouGiveItYourBest, MSGBOX_DEFAULT
 * goto TrainerHill_Entrance_EventScript_AttendantEnd
 * ```
 */
internal object TrainerHill_Entrance_EventScript_Attendant : Script {
  override suspend fun run(ctx: ScriptContext) =
      TODO("port TrainerHill_Entrance_EventScript_Attendant")
}

/** The decomp's Pokemon Center nurse: heal the party. */
internal object TrainerHill_Entrance_EventScript_Nurse : Script {
  override suspend fun run(ctx: ScriptContext) {
    healAtPokemonCenter(ctx)
  }
}

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object TrainerHill_Entrance_EventScript_Clerk : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.gText_HowMayIServeYou)
    if (ctx.isFlagSet(HoennFlags.FLAG_SYS_GAME_CLEAR)) {
      ctx.pokemart(
          Items.HYPER_POTION,
          Items.MAX_POTION,
          Items.FULL_RESTORE,
          Items.FULL_HEAL,
          Items.REVIVE,
          Items.X_SPEED,
          Items.X_SPECIAL,
          Items.X_ATTACK,
          Items.X_DEFEND,
          Items.DIRE_HIT,
          Items.GUARD_SPEC,
          Items.X_ACCURACY)
      return
    }
    ctx.pokemart(
        Items.SUPER_POTION,
        Items.ANTIDOTE,
        Items.PARLYZ_HEAL,
        Items.AWAKENING,
        Items.X_SPEED,
        Items.X_SPECIAL,
        Items.X_ATTACK,
        Items.X_DEFEND,
        Items.DIRE_HIT,
        Items.GUARD_SPEC,
        Items.X_ACCURACY)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * goto_if_unset FLAG_SYS_GAME_CLEAR, TrainerHill_Entrance_EventScript_GirlTrainerHillClosed
 * msgbox TrainerHill_Entrance_Text_FriendsTryingToReachTimeBoardTop, MSGBOX_NPC
 * end
 * ```
 */
internal object TrainerHill_Entrance_EventScript_Girl : Script {
  override suspend fun run(ctx: ScriptContext) = TODO("port TrainerHill_Entrance_EventScript_Girl")
}

/**
 * Not ported yet. Decomp body:
 * ```
 * goto_if_unset FLAG_SYS_GAME_CLEAR, TrainerHill_Entrance_EventScript_ManTrainerHillClosed
 * msgbox TrainerHill_Entrance_Text_WhatSortOfTrainersAreAhead, MSGBOX_NPC
 * end
 * ```
 */
internal object TrainerHill_Entrance_EventScript_Man : Script {
  override suspend fun run(ctx: ScriptContext) = TODO("port TrainerHill_Entrance_EventScript_Man")
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lockall
 * fadescreen FADE_TO_BLACK
 * special ShowTrainerHillRecords
 * releaseall
 * end
 * ```
 */
internal object TrainerHill_Entrance_EventScript_Records : Script {
  override suspend fun run(ctx: ScriptContext) =
      TODO("port TrainerHill_Entrance_EventScript_Records")
}

internal val TrainerHill_EntranceScripts: Map<String, Script> =
    mapOf(
        "TrainerHill_Entrance_EventScript_Attendant" to TrainerHill_Entrance_EventScript_Attendant,
        "TrainerHill_Entrance_EventScript_Nurse" to TrainerHill_Entrance_EventScript_Nurse,
        "TrainerHill_Entrance_EventScript_Clerk" to TrainerHill_Entrance_EventScript_Clerk,
        "TrainerHill_Entrance_EventScript_Girl" to TrainerHill_Entrance_EventScript_Girl,
        "TrainerHill_Entrance_EventScript_Man" to TrainerHill_Entrance_EventScript_Man,
        "TrainerHill_Entrance_EventScript_Records" to TrainerHill_Entrance_EventScript_Records,
    )
