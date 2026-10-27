package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Route19
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route19_EventScript_RouteSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route19.RouteSign)
}

internal val Route19Scripts: Map<String, Script> =
    mapOf(
        "Route19_EventScript_RouteSign" to Route19_EventScript_RouteSign,
    )
