package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Kecleon
import de.fiereu.openmmo.dialog.generated.hoenn.Route120
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route120_EventScript_BridgeKecleon : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Kecleon.SomethingUnseeable)
}

internal object Route120_EventScript_RouteSignFortree : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route120.RouteSignFortree)
}

internal object Route120_EventScript_RouteSign121 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route120.RouteSign121)
}

internal val Route120Scripts: Map<String, Script> =
    mapOf(
        "Route120_EventScript_BridgeKecleon" to Route120_EventScript_BridgeKecleon,
        "Route120_EventScript_RouteSignFortree" to Route120_EventScript_RouteSignFortree,
        "Route120_EventScript_RouteSign121" to Route120_EventScript_RouteSign121,
    )
