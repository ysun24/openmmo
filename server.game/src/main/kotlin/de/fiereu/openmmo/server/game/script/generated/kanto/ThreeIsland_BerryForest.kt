package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.ThreeIsland_BerryForest
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object ThreeIsland_BerryForest_EventScript_BewareSign : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(ThreeIsland_BerryForest.BewareWildBerryLovingMons)
}

internal object ThreeIsland_BerryForest_EventScript_WelcomeSign : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(ThreeIsland_BerryForest.WelcomeToBerryForest)
}

internal val ThreeIsland_BerryForestScripts: Map<String, Script> =
    mapOf(
        "ThreeIsland_BerryForest_EventScript_BewareSign" to
            ThreeIsland_BerryForest_EventScript_BewareSign,
        "ThreeIsland_BerryForest_EventScript_WelcomeSign" to
            ThreeIsland_BerryForest_EventScript_WelcomeSign,
    )
