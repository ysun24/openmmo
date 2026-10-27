package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Misc
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext
import de.fiereu.openmmo.story.generated.hoenn.HoennFlags

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

internal val TrainerHill_EntranceScripts: Map<String, Script> =
    mapOf(
        "TrainerHill_Entrance_EventScript_Nurse" to TrainerHill_Entrance_EventScript_Nurse,
        "TrainerHill_Entrance_EventScript_Clerk" to TrainerHill_Entrance_EventScript_Clerk,
    )
