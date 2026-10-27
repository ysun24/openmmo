package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Route14
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route14_EventScript_RouteSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route14.RouteSign)
}

internal val Route14Scripts: Map<String, Script> =
    mapOf(
        "Route14_EventScript_RouteSign" to Route14_EventScript_RouteSign,
    )
