package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.SilphCo_7F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SilphCo_7F_EventScript_FloorSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SilphCo_7F.FloorSign)
}

internal val SilphCo_7FScripts: Map<String, Script> =
    mapOf(
        "SilphCo_7F_EventScript_FloorSign" to SilphCo_7F_EventScript_FloorSign,
    )
