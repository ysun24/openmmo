package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.PokemonMansion_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object PokemonMansion_2F_EventScript_DiaryJuly10th : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(PokemonMansion_1F.ChristenedDiscoveredMonMew)
}

internal object PokemonMansion_2F_EventScript_DiaryJuly5th : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(PokemonMansion_1F.NewMonDiscoveredInGuyanaJungle)
}

internal val PokemonMansion_2FScripts: Map<String, Script> =
    mapOf(
        "PokemonMansion_2F_EventScript_DiaryJuly10th" to
            PokemonMansion_2F_EventScript_DiaryJuly10th,
        "PokemonMansion_2F_EventScript_DiaryJuly5th" to PokemonMansion_2F_EventScript_DiaryJuly5th,
    )
