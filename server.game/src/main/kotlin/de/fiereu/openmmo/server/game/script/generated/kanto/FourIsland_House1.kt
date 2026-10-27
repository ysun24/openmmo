package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.FourIsland_House1
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object FourIsland_House1_EventScript_FatMan : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(FourIsland_House1.YoureAwfullyHeavy)
}

internal val FourIsland_House1Scripts: Map<String, Script> =
    mapOf(
        "FourIsland_House1_EventScript_FatMan" to FourIsland_House1_EventScript_FatMan,
    )
