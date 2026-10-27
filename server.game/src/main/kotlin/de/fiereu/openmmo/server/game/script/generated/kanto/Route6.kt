package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Route6
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route6_EventScript_UndergroundPathSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route6.UndergroundPathSign)
}

internal val Route6Scripts: Map<String, Script> =
    mapOf(
        "Route6_EventScript_UndergroundPathSign" to Route6_EventScript_UndergroundPathSign,
    )
