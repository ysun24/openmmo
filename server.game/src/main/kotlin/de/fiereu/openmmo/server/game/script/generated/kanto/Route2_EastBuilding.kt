package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Route2_EastBuilding
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route2_EastBuilding_EventScript_Rocker : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(Route2_EastBuilding.CanGetThroughRockTunnel)
}

internal val Route2_EastBuildingScripts: Map<String, Script> =
    mapOf(
        "Route2_EastBuilding_EventScript_Rocker" to Route2_EastBuilding_EventScript_Rocker,
    )
