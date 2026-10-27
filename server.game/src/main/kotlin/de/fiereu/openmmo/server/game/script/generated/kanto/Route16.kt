package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Route16
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route16_EventScript_RouteSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route16.RouteSign)
}

internal object Route16_EventScript_CyclingRoadSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route16.CyclingRoadSign)
}

internal val Route16Scripts: Map<String, Script> =
    mapOf(
        "Route16_EventScript_RouteSign" to Route16_EventScript_RouteSign,
        "Route16_EventScript_CyclingRoadSign" to Route16_EventScript_CyclingRoadSign,
    )
