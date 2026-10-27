package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.RockTunnel_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object RockTunnel_1F_EventScript_RouteSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(RockTunnel_1F.RouteSign)
}

internal val RockTunnel_1FScripts: Map<String, Script> =
    mapOf(
        "RockTunnel_1F_EventScript_RouteSign" to RockTunnel_1F_EventScript_RouteSign,
    )
