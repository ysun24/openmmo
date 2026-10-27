package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.PokemonTower_5F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object PokemonTower_5F_EventScript_Channeler : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(PokemonTower_5F.RestHereInPurifiedSpace)
}

internal val PokemonTower_5FScripts: Map<String, Script> =
    mapOf(
        "PokemonTower_5F_EventScript_Channeler" to PokemonTower_5F_EventScript_Channeler,
    )
