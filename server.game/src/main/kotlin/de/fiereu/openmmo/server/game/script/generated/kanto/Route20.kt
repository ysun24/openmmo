package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Route20
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route20_EventScript_SeafoamIslandsSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route20.SeafoamIslands)
}

internal val Route20Scripts: Map<String, Script> =
    mapOf(
        "Route20_EventScript_SeafoamIslandsSign" to Route20_EventScript_SeafoamIslandsSign,
    )
