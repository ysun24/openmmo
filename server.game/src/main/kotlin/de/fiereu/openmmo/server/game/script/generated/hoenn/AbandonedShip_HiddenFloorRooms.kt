package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.AbandonedShip_HiddenFloorRooms
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object AbandonedShip_HiddenFloorRooms_EventScript_Trash : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(AbandonedShip_HiddenFloorRooms.BrightShinyTrash)
}

internal val AbandonedShip_HiddenFloorRoomsScripts: Map<String, Script> =
    mapOf(
        "AbandonedShip_HiddenFloorRooms_EventScript_Trash" to
            AbandonedShip_HiddenFloorRooms_EventScript_Trash,
    )
