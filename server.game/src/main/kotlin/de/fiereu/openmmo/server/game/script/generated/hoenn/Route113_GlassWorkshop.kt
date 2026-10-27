package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Route113_GlassWorkshop
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route113_GlassWorkshop_EventScript_NinjaBoy : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route113_GlassWorkshop.FunToBlowGlassFlute)
}

internal val Route113_GlassWorkshopScripts: Map<String, Script> =
    mapOf(
        "Route113_GlassWorkshop_EventScript_NinjaBoy" to
            Route113_GlassWorkshop_EventScript_NinjaBoy,
    )
