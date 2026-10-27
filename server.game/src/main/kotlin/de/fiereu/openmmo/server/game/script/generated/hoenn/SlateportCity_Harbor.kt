package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.SlateportCity_Harbor
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SlateportCity_Harbor_EventScript_FatMan : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(SlateportCity_Harbor.SubTooSmallForMe)
}

internal val SlateportCity_HarborScripts: Map<String, Script> =
    mapOf(
        "SlateportCity_Harbor_EventScript_FatMan" to SlateportCity_Harbor_EventScript_FatMan,
    )
