package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.ThreeIsland_House1
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object ThreeIsland_House1_EventScript_DisplayCase : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(ThreeIsland_House1.RareRocksGemsDisplayed)
}

internal val ThreeIsland_House1Scripts: Map<String, Script> =
    mapOf(
        "ThreeIsland_House1_EventScript_DisplayCase" to ThreeIsland_House1_EventScript_DisplayCase,
    )
