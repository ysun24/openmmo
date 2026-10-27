package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.EverGrandeCity_PokemonCenter_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's Pokemon Center nurse: heal the party. */
internal object EverGrandeCity_PokemonCenter_1F_EventScript_Nurse : Script {
  override suspend fun run(ctx: ScriptContext) {
    healAtPokemonCenter(ctx)
  }
}

internal object EverGrandeCity_PokemonCenter_1F_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(EverGrandeCity_PokemonCenter_1F.LeagueAfterVictoryRoad)
}

internal object EverGrandeCity_PokemonCenter_1F_EventScript_ExpertM : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(EverGrandeCity_PokemonCenter_1F.BelieveInYourPokemon)
}

internal val EverGrandeCity_PokemonCenter_1FScripts: Map<String, Script> =
    mapOf(
        "EverGrandeCity_PokemonCenter_1F_EventScript_Nurse" to
            EverGrandeCity_PokemonCenter_1F_EventScript_Nurse,
        "EverGrandeCity_PokemonCenter_1F_EventScript_Woman" to
            EverGrandeCity_PokemonCenter_1F_EventScript_Woman,
        "EverGrandeCity_PokemonCenter_1F_EventScript_ExpertM" to
            EverGrandeCity_PokemonCenter_1F_EventScript_ExpertM,
    )
