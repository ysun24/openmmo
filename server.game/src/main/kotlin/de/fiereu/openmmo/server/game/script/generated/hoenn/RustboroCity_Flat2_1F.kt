package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.RustboroCity_Flat2_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object RustboroCity_Flat2_1F_EventScript_OldWoman : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(RustboroCity_Flat2_1F.DevonWorkersLiveHere)
}

internal val RustboroCity_Flat2_1FScripts: Map<String, Script> =
    mapOf(
        "RustboroCity_Flat2_1F_EventScript_OldWoman" to RustboroCity_Flat2_1F_EventScript_OldWoman,
    )
