package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Route8
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route8_EventScript_UndergroundPathSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route8.UndergroundPathSign)
}

internal val Route8Scripts: Map<String, Script> =
    mapOf(
        "Route8_EventScript_UndergroundPathSign" to Route8_EventScript_UndergroundPathSign,
    )
