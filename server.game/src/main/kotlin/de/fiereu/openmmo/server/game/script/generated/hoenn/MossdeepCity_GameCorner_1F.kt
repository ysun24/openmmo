package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.RS_MysteryEventsHouse
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object RS_MysteryEventsHouse_EventScript_Door : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(RS_MysteryEventsHouse.DoorIsLocked)
}

internal val MossdeepCity_GameCorner_1FScripts: Map<String, Script> =
    mapOf(
        "RS_MysteryEventsHouse_EventScript_Door" to RS_MysteryEventsHouse_EventScript_Door,
    )
