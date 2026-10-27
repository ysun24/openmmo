package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.FortreeCity_House5
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object FortreeCity_House5_EventScript_PokefanF : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(FortreeCity_House5.TreeHousesAreGreat)
}

internal object FortreeCity_House5_EventScript_Man : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(FortreeCity_House5.AdaptedToNature)
}

internal val FortreeCity_House5Scripts: Map<String, Script> =
    mapOf(
        "FortreeCity_House5_EventScript_PokefanF" to FortreeCity_House5_EventScript_PokefanF,
        "FortreeCity_House5_EventScript_Man" to FortreeCity_House5_EventScript_Man,
    )
