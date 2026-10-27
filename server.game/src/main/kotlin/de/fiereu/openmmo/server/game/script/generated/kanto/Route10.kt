package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Route10
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route10_EventScript_SouthRockTunnelSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route10.RockTunnel)
}

internal object Route10_EventScript_PowerPlantSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route10.PowerPlant)
}

internal object Route10_EventScript_NorthRockTunnelSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route10.RockTunnelDetourToLavender)
}

internal val Route10Scripts: Map<String, Script> =
    mapOf(
        "Route10_EventScript_SouthRockTunnelSign" to Route10_EventScript_SouthRockTunnelSign,
        "Route10_EventScript_PowerPlantSign" to Route10_EventScript_PowerPlantSign,
        "Route10_EventScript_NorthRockTunnelSign" to Route10_EventScript_NorthRockTunnelSign,
    )
