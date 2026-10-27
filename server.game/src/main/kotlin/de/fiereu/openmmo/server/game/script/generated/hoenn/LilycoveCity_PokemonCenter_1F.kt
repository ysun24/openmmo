package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.LilycoveCity_PokemonCenter_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's Pokemon Center nurse: heal the party. */
internal object LilycoveCity_PokemonCenter_1F_EventScript_Nurse : Script {
  override suspend fun run(ctx: ScriptContext) {
    healAtPokemonCenter(ctx)
  }
}

internal object LilycoveCity_PokemonCenter_1F_EventScript_Boy : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_PokemonCenter_1F.HowManyKindsOfPokemon)
}

internal val LilycoveCity_PokemonCenter_1FScripts: Map<String, Script> =
    mapOf(
        "LilycoveCity_PokemonCenter_1F_EventScript_Nurse" to
            LilycoveCity_PokemonCenter_1F_EventScript_Nurse,
        "LilycoveCity_PokemonCenter_1F_EventScript_Boy" to
            LilycoveCity_PokemonCenter_1F_EventScript_Boy,
    )
