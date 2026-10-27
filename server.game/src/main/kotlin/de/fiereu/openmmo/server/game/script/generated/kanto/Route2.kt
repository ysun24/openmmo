package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Route2
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route2_EventScript_DiglettsCaveSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route2.DiglettsCave)
}

internal object Route2_EventScript_RouteSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route2.RouteSign)
}

internal val Route2Scripts: Map<String, Script> =
    mapOf(
        "Route2_EventScript_DiglettsCaveSign" to Route2_EventScript_DiglettsCaveSign,
        "Route2_EventScript_RouteSign" to Route2_EventScript_RouteSign,
    )
