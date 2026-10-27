package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Route23
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route23_EventScript_VictoryRoadGateSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route23.VictoryRoadGateSign)
}

internal val Route23Scripts: Map<String, Script> =
    mapOf(
        "Route23_EventScript_VictoryRoadGateSign" to Route23_EventScript_VictoryRoadGateSign,
    )
