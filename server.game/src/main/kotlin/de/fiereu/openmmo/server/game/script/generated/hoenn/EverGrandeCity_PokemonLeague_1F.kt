package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Misc
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's Pokemon Center nurse: heal the party. */
internal object EverGrandeCity_PokemonLeague_1F_EventScript_Nurse : Script {
  override suspend fun run(ctx: ScriptContext) {
    healAtPokemonCenter(ctx)
  }
}

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object EverGrandeCity_PokemonLeague_1F_EventScript_Clerk : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.gText_HowMayIServeYou)
    ctx.pokemart(
        Items.ULTRA_BALL,
        Items.HYPER_POTION,
        Items.MAX_POTION,
        Items.FULL_RESTORE,
        Items.FULL_HEAL,
        Items.REVIVE,
        Items.MAX_REPEL)
  }
}

internal val EverGrandeCity_PokemonLeague_1FScripts: Map<String, Script> =
    mapOf(
        "EverGrandeCity_PokemonLeague_1F_EventScript_Nurse" to
            EverGrandeCity_PokemonLeague_1F_EventScript_Nurse,
        "EverGrandeCity_PokemonLeague_1F_EventScript_Clerk" to
            EverGrandeCity_PokemonLeague_1F_EventScript_Clerk,
    )
