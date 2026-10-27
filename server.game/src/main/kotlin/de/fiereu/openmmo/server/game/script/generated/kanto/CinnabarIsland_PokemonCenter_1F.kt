package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.CinnabarIsland_PokemonCenter_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's Pokemon Center nurse: heal the party. */
internal object CinnabarIsland_PokemonCenter_1F_EventScript_Nurse : Script {
  override suspend fun run(ctx: ScriptContext) {
    healAtKantoPokemonCenter(ctx)
  }
}

internal object CinnabarIsland_PokemonCenter_1F_EventScript_CooltrainerF : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(CinnabarIsland_PokemonCenter_1F.CinnabarGymLocked)
}

internal object CinnabarIsland_PokemonCenter_1F_EventScript_Gentleman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(CinnabarIsland_PokemonCenter_1F.VisitUnionRoom)
}

internal object CinnabarIsland_PokemonCenter_1F_EventScript_Youngster : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(CinnabarIsland_PokemonCenter_1F.EvolutionCanWaitForNewMoves)
}

internal val CinnabarIsland_PokemonCenter_1FScripts: Map<String, Script> =
    mapOf(
        "CinnabarIsland_PokemonCenter_1F_EventScript_Nurse" to
            CinnabarIsland_PokemonCenter_1F_EventScript_Nurse,
        "CinnabarIsland_PokemonCenter_1F_EventScript_CooltrainerF" to
            CinnabarIsland_PokemonCenter_1F_EventScript_CooltrainerF,
        "CinnabarIsland_PokemonCenter_1F_EventScript_Gentleman" to
            CinnabarIsland_PokemonCenter_1F_EventScript_Gentleman,
        "CinnabarIsland_PokemonCenter_1F_EventScript_Youngster" to
            CinnabarIsland_PokemonCenter_1F_EventScript_Youngster,
    )
