package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.RustboroCity_DevonCorp_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object RustboroCity_DevonCorp_1F_EventScript_ProductsDisplay : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(RustboroCity_DevonCorp_1F.ProductDisplay)
}

internal object RustboroCity_DevonCorp_1F_EventScript_RocksMetalDisplay : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(RustboroCity_DevonCorp_1F.RocksMetalDisplay)
}

internal val RustboroCity_DevonCorp_1FScripts: Map<String, Script> =
    mapOf(
        "RustboroCity_DevonCorp_1F_EventScript_ProductsDisplay" to
            RustboroCity_DevonCorp_1F_EventScript_ProductsDisplay,
        "RustboroCity_DevonCorp_1F_EventScript_RocksMetalDisplay" to
            RustboroCity_DevonCorp_1F_EventScript_RocksMetalDisplay,
    )
