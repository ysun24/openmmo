package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Route11_EastEntrance_2F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route11_EastEntrance_2F_EventScript_RightBinoculars : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(Route11_EastEntrance_2F.RockTunnelGoodRouteToLavender)
}

internal val Route11_EastEntrance_2FScripts: Map<String, Script> =
    mapOf(
        "Route11_EastEntrance_2F_EventScript_RightBinoculars" to
            Route11_EastEntrance_2F_EventScript_RightBinoculars,
    )
