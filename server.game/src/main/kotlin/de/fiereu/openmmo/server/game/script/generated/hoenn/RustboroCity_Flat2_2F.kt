package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.RustboroCity_Flat2_2F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object RustboroCity_Flat2_2F_EventScript_OldMan : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(RustboroCity_Flat2_2F.DevonWasTinyInOldDays)
}

internal val RustboroCity_Flat2_2FScripts: Map<String, Script> =
    mapOf(
        "RustboroCity_Flat2_2F_EventScript_OldMan" to RustboroCity_Flat2_2F_EventScript_OldMan,
    )
