package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.CeladonCity_Condominiums_2F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object CeladonCity_Condominiums_2F_EventScript_MeetingRoomSign : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(CeladonCity_Condominiums_2F.GameFreakMeetingRoom)
}

internal val CeladonCity_Condominiums_2FScripts: Map<String, Script> =
    mapOf(
        "CeladonCity_Condominiums_2F_EventScript_MeetingRoomSign" to
            CeladonCity_Condominiums_2F_EventScript_MeetingRoomSign,
    )
