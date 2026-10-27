package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Route116
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route116_EventScript_RouteSignRustboro : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route116.RouteSignRustboro)
}

internal object Route116_EventScript_RusturfTunnelSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route116.RusturfTunnelSign)
}

internal object Route116_EventScript_TunnelersRestHouseSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route116.TunnelersRestHouse)
}

internal object Route116_EventScript_TrainerTipsBToStopEvolution : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route116.TrainerTipsBToStopEvolution)
}

internal object Route116_EventScript_TrainerTipsBagHasPockets : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route116.TrainerTipsBagHasPockets)
}

internal val Route116Scripts: Map<String, Script> =
    mapOf(
        "Route116_EventScript_RouteSignRustboro" to Route116_EventScript_RouteSignRustboro,
        "Route116_EventScript_RusturfTunnelSign" to Route116_EventScript_RusturfTunnelSign,
        "Route116_EventScript_TunnelersRestHouseSign" to
            Route116_EventScript_TunnelersRestHouseSign,
        "Route116_EventScript_TrainerTipsBToStopEvolution" to
            Route116_EventScript_TrainerTipsBToStopEvolution,
        "Route116_EventScript_TrainerTipsBagHasPockets" to
            Route116_EventScript_TrainerTipsBagHasPockets,
    )
