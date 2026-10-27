package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.OneIsland
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object OneIsland_EventScript_BaldingMan : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(OneIsland.IsntWarmClimateHereGreat)
}

internal object OneIsland_EventScript_IslandSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(OneIsland.IslandSign)
}

internal object OneIsland_EventScript_PokemonNetCenterSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(OneIsland.PokemonNetCenterSign)
}

internal val OneIslandScripts: Map<String, Script> =
    mapOf(
        "OneIsland_EventScript_BaldingMan" to OneIsland_EventScript_BaldingMan,
        "OneIsland_EventScript_IslandSign" to OneIsland_EventScript_IslandSign,
        "OneIsland_EventScript_PokemonNetCenterSign" to OneIsland_EventScript_PokemonNetCenterSign,
    )
