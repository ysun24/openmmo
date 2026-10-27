package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Route11
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route11_EventScript_DiglettsCaveSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route11.DiglettsCave)
}

internal val Route11Scripts: Map<String, Script> =
    mapOf(
        "Route11_EventScript_DiglettsCaveSign" to Route11_EventScript_DiglettsCaveSign,
    )
