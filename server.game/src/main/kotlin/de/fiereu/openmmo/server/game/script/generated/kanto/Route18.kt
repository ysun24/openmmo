package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Route18
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route18_EventScript_CyclingRoadSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route18.CyclingRoadSign)
}

internal object Route18_EventScript_RouteSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route18.RouteSign)
}

internal val Route18Scripts: Map<String, Script> =
    mapOf(
        "Route18_EventScript_CyclingRoadSign" to Route18_EventScript_CyclingRoadSign,
        "Route18_EventScript_RouteSign" to Route18_EventScript_RouteSign,
    )
