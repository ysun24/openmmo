package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.SevenIsland_House_Room1
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SevenIsland_House_Room1_EventScript_Box : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(SevenIsland_House_Room1.SlightBreezeAroundBox)
}

internal val SevenIsland_House_Room1Scripts: Map<String, Script> =
    mapOf(
        "SevenIsland_House_Room1_EventScript_Box" to SevenIsland_House_Room1_EventScript_Box,
    )
