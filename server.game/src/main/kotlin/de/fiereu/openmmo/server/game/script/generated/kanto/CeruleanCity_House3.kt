package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.CeruleanCity_House3
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object CeruleanCity_House3_EventScript_OldWoman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(CeruleanCity_House3.PleaseTradeWithMyHusband)
}

internal val CeruleanCity_House3Scripts: Map<String, Script> =
    mapOf(
        "CeruleanCity_House3_EventScript_OldWoman" to CeruleanCity_House3_EventScript_OldWoman,
    )
