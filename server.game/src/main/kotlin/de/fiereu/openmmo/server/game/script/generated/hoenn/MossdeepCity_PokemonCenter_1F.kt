package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.MossdeepCity_PokemonCenter_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's Pokemon Center nurse: heal the party. */
internal object MossdeepCity_PokemonCenter_1F_EventScript_Nurse : Script {
  override suspend fun run(ctx: ScriptContext) {
    healAtPokemonCenter(ctx)
  }
}

internal object MossdeepCity_PokemonCenter_1F_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(MossdeepCity_PokemonCenter_1F.GymLeaderDuoFormidable)
}

internal object MossdeepCity_PokemonCenter_1F_EventScript_Girl : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(MossdeepCity_PokemonCenter_1F.AbilitiesMightChangeMoves)
}

internal val MossdeepCity_PokemonCenter_1FScripts: Map<String, Script> =
    mapOf(
        "MossdeepCity_PokemonCenter_1F_EventScript_Nurse" to
            MossdeepCity_PokemonCenter_1F_EventScript_Nurse,
        "MossdeepCity_PokemonCenter_1F_EventScript_Woman" to
            MossdeepCity_PokemonCenter_1F_EventScript_Woman,
        "MossdeepCity_PokemonCenter_1F_EventScript_Girl" to
            MossdeepCity_PokemonCenter_1F_EventScript_Girl,
    )
