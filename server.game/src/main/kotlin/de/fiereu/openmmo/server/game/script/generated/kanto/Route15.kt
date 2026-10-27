package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Route15
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route15_EventScript_RouteSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route15.RouteSign)
}

internal val Route15Scripts: Map<String, Script> =
    mapOf(
        "Route15_EventScript_RouteSign" to Route15_EventScript_RouteSign,
    )
