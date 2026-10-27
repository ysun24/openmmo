package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.SafariZone_North
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SafariZone_North_EventScript_Fisherman : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(SafariZone_North.Fisherman)
}

internal object SafariZone_North_EventScript_Man : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(SafariZone_North.Man)
}

internal val SafariZone_NorthScripts: Map<String, Script> =
    mapOf(
        "SafariZone_North_EventScript_Fisherman" to SafariZone_North_EventScript_Fisherman,
        "SafariZone_North_EventScript_Man" to SafariZone_North_EventScript_Man,
    )
