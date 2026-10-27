package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.CeladonCity_GameCorner_PrizeRoom
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object CeladonCity_GameCorner_PrizeRoom_EventScript_BaldingMan : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(CeladonCity_GameCorner_PrizeRoom.FancyThatPorygon)
}

internal object CeladonCity_GameCorner_PrizeRoom_EventScript_OldMan : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(CeladonCity_GameCorner_PrizeRoom.RakedItInToday)
}

internal val CeladonCity_GameCorner_PrizeRoomScripts: Map<String, Script> =
    mapOf(
        "CeladonCity_GameCorner_PrizeRoom_EventScript_BaldingMan" to
            CeladonCity_GameCorner_PrizeRoom_EventScript_BaldingMan,
        "CeladonCity_GameCorner_PrizeRoom_EventScript_OldMan" to
            CeladonCity_GameCorner_PrizeRoom_EventScript_OldMan,
    )
