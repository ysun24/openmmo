package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.PewterCity_House1
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object PewterCity_House1_EventScript_BaldingMan : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(PewterCity_House1.TradeMonsAreFinicky)
}

internal val PewterCity_House1Scripts: Map<String, Script> =
    mapOf(
        "PewterCity_House1_EventScript_BaldingMan" to PewterCity_House1_EventScript_BaldingMan,
    )
