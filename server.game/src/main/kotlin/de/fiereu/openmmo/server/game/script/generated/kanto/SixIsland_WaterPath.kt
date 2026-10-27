package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.SixIsland_WaterPath
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SixIsland_WaterPath_EventScript_HornWantedSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SixIsland_WaterPath.WantedUltimateHorn)
}

internal object SixIsland_WaterPath_EventScript_RouteSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SixIsland_WaterPath.RouteSign)
}

internal val SixIsland_WaterPathScripts: Map<String, Script> =
    mapOf(
        "SixIsland_WaterPath_EventScript_HornWantedSign" to
            SixIsland_WaterPath_EventScript_HornWantedSign,
        "SixIsland_WaterPath_EventScript_RouteSign" to SixIsland_WaterPath_EventScript_RouteSign,
    )
