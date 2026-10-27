package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.PokemonMansion_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object PokemonMansion_3F_EventScript_DiaryFeb6th : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(PokemonMansion_1F.MewGaveBirthToMewtwo)
}

internal val PokemonMansion_3FScripts: Map<String, Script> =
    mapOf(
        "PokemonMansion_3F_EventScript_DiaryFeb6th" to PokemonMansion_3F_EventScript_DiaryFeb6th,
    )
