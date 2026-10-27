package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.FiveIsland_RocketWarehouse
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object FiveIsland_RocketWarehouse_EventScript_Computer : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(FiveIsland_RocketWarehouse.ReportWithGraphsAndText)
}

internal val FiveIsland_RocketWarehouseScripts: Map<String, Script> =
    mapOf(
        "FiveIsland_RocketWarehouse_EventScript_Computer" to
            FiveIsland_RocketWarehouse_EventScript_Computer,
    )
