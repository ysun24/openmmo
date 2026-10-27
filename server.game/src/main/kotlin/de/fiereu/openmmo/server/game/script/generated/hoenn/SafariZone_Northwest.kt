package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.SafariZone_Northwest
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SafariZone_Northwest_EventScript_Man : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(SafariZone_Northwest.Man)
}

internal val SafariZone_NorthwestScripts: Map<String, Script> =
    mapOf(
        "SafariZone_Northwest_EventScript_Man" to SafariZone_Northwest_EventScript_Man,
    )
