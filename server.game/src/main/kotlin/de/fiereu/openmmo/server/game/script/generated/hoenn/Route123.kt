package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Route123
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route123_EventScript_RouteSignMtPyre : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route123.RouteSignMtPyre)
}

internal object Route123_EventScript_RouteSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route123.RouteSign)
}

internal object Route123_EventScript_BerryMastersHouseSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route123.BerryMastersHouse)
}

internal val Route123Scripts: Map<String, Script> =
    mapOf(
        "Route123_EventScript_RouteSignMtPyre" to Route123_EventScript_RouteSignMtPyre,
        "Route123_EventScript_RouteSign" to Route123_EventScript_RouteSign,
        "Route123_EventScript_BerryMastersHouseSign" to Route123_EventScript_BerryMastersHouseSign,
    )
