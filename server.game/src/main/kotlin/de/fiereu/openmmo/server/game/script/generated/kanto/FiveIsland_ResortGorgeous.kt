package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.FiveIsland_ResortGorgeous
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object FiveIsland_ResortGorgeous_EventScript_SelphysHouseSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(FiveIsland_ResortGorgeous.SelphysHouse)
}

internal val FiveIsland_ResortGorgeousScripts: Map<String, Script> =
    mapOf(
        "FiveIsland_ResortGorgeous_EventScript_SelphysHouseSign" to
            FiveIsland_ResortGorgeous_EventScript_SelphysHouseSign,
    )
