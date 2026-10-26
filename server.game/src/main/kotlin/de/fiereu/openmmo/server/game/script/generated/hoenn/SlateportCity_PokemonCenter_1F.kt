package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.SlateportCity_PokemonCenter_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's Pokemon Center nurse: heal the party. */
internal object SlateportCity_PokemonCenter_1F_EventScript_Nurse : Script {
  override suspend fun run(ctx: ScriptContext) {
    healAtPokemonCenter(ctx)
  }
}

internal object SlateportCity_PokemonCenter_1F_EventScript_Sailor : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(SlateportCity_PokemonCenter_1F.RaiseDifferentTypesOfPokemon)
}

internal object SlateportCity_PokemonCenter_1F_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(SlateportCity_PokemonCenter_1F.TradedMonWithFriend)
}

internal val SlateportCity_PokemonCenter_1FScripts: Map<String, Script> =
    mapOf(
        "SlateportCity_PokemonCenter_1F_EventScript_Nurse" to
            SlateportCity_PokemonCenter_1F_EventScript_Nurse,
        "SlateportCity_PokemonCenter_1F_EventScript_Sailor" to
            SlateportCity_PokemonCenter_1F_EventScript_Sailor,
        "SlateportCity_PokemonCenter_1F_EventScript_Woman" to
            SlateportCity_PokemonCenter_1F_EventScript_Woman,
    )
