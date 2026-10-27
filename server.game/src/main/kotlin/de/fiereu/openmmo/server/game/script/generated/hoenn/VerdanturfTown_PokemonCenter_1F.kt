package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.VerdanturfTown_PokemonCenter_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's Pokemon Center nurse: heal the party. */
internal object VerdanturfTown_PokemonCenter_1F_EventScript_Nurse : Script {
  override suspend fun run(ctx: ScriptContext) {
    healAtPokemonCenter(ctx)
  }
}

internal object VerdanturfTown_PokemonCenter_1F_EventScript_Gentleman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(VerdanturfTown_PokemonCenter_1F.FaithInYourPokemon)
}

internal object VerdanturfTown_PokemonCenter_1F_EventScript_ExpertM : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(VerdanturfTown_PokemonCenter_1F.VisitForBattleTent)
}

internal val VerdanturfTown_PokemonCenter_1FScripts: Map<String, Script> =
    mapOf(
        "VerdanturfTown_PokemonCenter_1F_EventScript_Nurse" to
            VerdanturfTown_PokemonCenter_1F_EventScript_Nurse,
        "VerdanturfTown_PokemonCenter_1F_EventScript_Gentleman" to
            VerdanturfTown_PokemonCenter_1F_EventScript_Gentleman,
        "VerdanturfTown_PokemonCenter_1F_EventScript_ExpertM" to
            VerdanturfTown_PokemonCenter_1F_EventScript_ExpertM,
    )
