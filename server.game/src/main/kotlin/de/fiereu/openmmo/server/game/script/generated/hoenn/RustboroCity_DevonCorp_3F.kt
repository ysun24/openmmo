package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.RustboroCity_DevonCorp_3F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object RustboroCity_DevonCorp_3F_EventScript_RareRocksDisplay : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(RustboroCity_DevonCorp_3F.RareRocksDisplay)
}

internal val RustboroCity_DevonCorp_3FScripts: Map<String, Script> =
    mapOf(
        "RustboroCity_DevonCorp_3F_EventScript_RareRocksDisplay" to
            RustboroCity_DevonCorp_3F_EventScript_RareRocksDisplay,
    )
