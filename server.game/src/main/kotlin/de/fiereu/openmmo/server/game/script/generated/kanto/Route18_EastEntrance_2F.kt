package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Route18_EastEntrance_2F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route18_EastEntrance_2F_EventScript_LeftBinoculars : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route18_EastEntrance_2F.PalletTownInWest)
}

internal object Route18_EastEntrance_2F_EventScript_RightBinoculars : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route18_EastEntrance_2F.PeopleSwimming)
}

internal val Route18_EastEntrance_2FScripts: Map<String, Script> =
    mapOf(
        "Route18_EastEntrance_2F_EventScript_LeftBinoculars" to
            Route18_EastEntrance_2F_EventScript_LeftBinoculars,
        "Route18_EastEntrance_2F_EventScript_RightBinoculars" to
            Route18_EastEntrance_2F_EventScript_RightBinoculars,
    )
