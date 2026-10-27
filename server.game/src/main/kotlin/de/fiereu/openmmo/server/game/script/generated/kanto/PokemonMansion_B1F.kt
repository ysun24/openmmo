package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.PokemonMansion_B1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object PokemonMansion_B1F_EventScript_DiarySep1st : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(PokemonMansion_B1F.MewtwoIsFarTooPowerful)
}

internal val PokemonMansion_B1FScripts: Map<String, Script> =
    mapOf(
        "PokemonMansion_B1F_EventScript_DiarySep1st" to PokemonMansion_B1F_EventScript_DiarySep1st,
    )
