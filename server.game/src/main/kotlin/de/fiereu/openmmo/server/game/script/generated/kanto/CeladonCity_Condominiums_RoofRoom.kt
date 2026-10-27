package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.CeladonCity_Condominiums_RoofRoom
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object CeladonCity_Condominiums_RoofRoom_EventScript_BlackBelt : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(CeladonCity_Condominiums_RoofRoom.TheresNothingIDontKnow)
}

internal object CeladonCity_Condominiums_RoofRoom_EventScript_TMsPamphlet : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(CeladonCity_Condominiums_RoofRoom.PamphletOnTMs)
}

internal val CeladonCity_Condominiums_RoofRoomScripts: Map<String, Script> =
    mapOf(
        "CeladonCity_Condominiums_RoofRoom_EventScript_BlackBelt" to
            CeladonCity_Condominiums_RoofRoom_EventScript_BlackBelt,
        "CeladonCity_Condominiums_RoofRoom_EventScript_TMsPamphlet" to
            CeladonCity_Condominiums_RoofRoom_EventScript_TMsPamphlet,
    )
