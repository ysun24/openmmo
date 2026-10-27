package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Route12_NorthEntrance_2F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route12_NorthEntrance_2F_EventScript_LeftBinoculars : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route12_NorthEntrance_2F.TheresManFishing)
}

internal object Route12_NorthEntrance_2F_EventScript_RightBinoculars : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route12_NorthEntrance_2F.ItsPokemonTower)
}

internal val Route12_NorthEntrance_2FScripts: Map<String, Script> =
    mapOf(
        "Route12_NorthEntrance_2F_EventScript_LeftBinoculars" to
            Route12_NorthEntrance_2F_EventScript_LeftBinoculars,
        "Route12_NorthEntrance_2F_EventScript_RightBinoculars" to
            Route12_NorthEntrance_2F_EventScript_RightBinoculars,
    )
