package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.RustboroCity_House1
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object RustboroCity_House1_EventScript_Hiker : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(RustboroCity_House1.AllSortsOfPlaces)
}

internal val RustboroCity_House1Scripts: Map<String, Script> =
    mapOf(
        "RustboroCity_House1_EventScript_Hiker" to RustboroCity_House1_EventScript_Hiker,
    )
