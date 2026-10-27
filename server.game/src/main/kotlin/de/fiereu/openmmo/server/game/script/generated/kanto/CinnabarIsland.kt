package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.CinnabarIsland
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object CinnabarIsland_EventScript_OldMan : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(CinnabarIsland.ScientistsExperimentInMansion)
}

internal object CinnabarIsland_EventScript_IslandSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(CinnabarIsland.IslandSign)
}

internal object CinnabarIsland_EventScript_PokemonLabSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(CinnabarIsland.PokemonLab)
}

internal val CinnabarIslandScripts: Map<String, Script> =
    mapOf(
        "CinnabarIsland_EventScript_OldMan" to CinnabarIsland_EventScript_OldMan,
        "CinnabarIsland_EventScript_IslandSign" to CinnabarIsland_EventScript_IslandSign,
        "CinnabarIsland_EventScript_PokemonLabSign" to CinnabarIsland_EventScript_PokemonLabSign,
    )
