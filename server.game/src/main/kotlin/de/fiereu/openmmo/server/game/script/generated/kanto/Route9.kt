package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Route9
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route9_EventScript_RouteSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route9.RouteSign)
}

internal val Route9Scripts: Map<String, Script> =
    mapOf(
        "Route9_EventScript_RouteSign" to Route9_EventScript_RouteSign,
    )
