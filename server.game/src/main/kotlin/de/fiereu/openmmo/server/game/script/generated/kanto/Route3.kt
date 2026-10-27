package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Route3
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route3_EventScript_Youngster : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route3.TunnelFromCeruleanTiring)
}

internal object Route3_EventScript_RouteSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route3.RouteSign)
}

internal val Route3Scripts: Map<String, Script> =
    mapOf(
        "Route3_EventScript_Youngster" to Route3_EventScript_Youngster,
        "Route3_EventScript_RouteSign" to Route3_EventScript_RouteSign,
    )
