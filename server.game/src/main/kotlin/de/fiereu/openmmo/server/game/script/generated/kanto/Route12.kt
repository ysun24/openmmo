package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Route12
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route12_EventScript_RouteSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route12.RouteSign)
}

internal object Route12_EventScript_FishingSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route12.SportfishingArea)
}

internal val Route12Scripts: Map<String, Script> =
    mapOf(
        "Route12_EventScript_RouteSign" to Route12_EventScript_RouteSign,
        "Route12_EventScript_FishingSign" to Route12_EventScript_FishingSign,
    )
