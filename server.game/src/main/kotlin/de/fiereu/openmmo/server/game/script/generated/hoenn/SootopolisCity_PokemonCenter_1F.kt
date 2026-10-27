package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's Pokemon Center nurse: heal the party. */
internal object SootopolisCity_PokemonCenter_1F_EventScript_Nurse : Script {
  override suspend fun run(ctx: ScriptContext) {
    healAtPokemonCenter(ctx)
  }
}

internal val SootopolisCity_PokemonCenter_1FScripts: Map<String, Script> =
    mapOf(
        "SootopolisCity_PokemonCenter_1F_EventScript_Nurse" to
            SootopolisCity_PokemonCenter_1F_EventScript_Nurse,
    )
