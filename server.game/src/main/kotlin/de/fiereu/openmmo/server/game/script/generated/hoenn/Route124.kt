package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Route124
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route124_EventScript_HuntersHouseSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route124.HuntersHouse)
}

internal val Route124Scripts: Map<String, Script> =
    mapOf(
        "Route124_EventScript_HuntersHouseSign" to Route124_EventScript_HuntersHouseSign,
    )
