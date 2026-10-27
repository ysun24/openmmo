package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.SaffronCity_PokemonCenter_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's Pokemon Center nurse: heal the party. */
internal object SaffronCity_PokemonCenter_1F_EventScript_Nurse : Script {
  override suspend fun run(ctx: ScriptContext) {
    healAtKantoPokemonCenter(ctx)
  }
}

internal object SaffronCity_PokemonCenter_1F_EventScript_Gentleman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(SaffronCity_PokemonCenter_1F.SilphCoVictimOfFame)
}

internal object SaffronCity_PokemonCenter_1F_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(SaffronCity_PokemonCenter_1F.GrowthRatesDifferBySpecies)
}

internal val SaffronCity_PokemonCenter_1FScripts: Map<String, Script> =
    mapOf(
        "SaffronCity_PokemonCenter_1F_EventScript_Nurse" to
            SaffronCity_PokemonCenter_1F_EventScript_Nurse,
        "SaffronCity_PokemonCenter_1F_EventScript_Gentleman" to
            SaffronCity_PokemonCenter_1F_EventScript_Gentleman,
        "SaffronCity_PokemonCenter_1F_EventScript_Woman" to
            SaffronCity_PokemonCenter_1F_EventScript_Woman,
    )
