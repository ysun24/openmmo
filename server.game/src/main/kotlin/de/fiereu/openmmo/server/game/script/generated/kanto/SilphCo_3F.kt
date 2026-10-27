package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.SilphCo_3F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SilphCo_3F_EventScript_FloorSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SilphCo_3F.FloorSign)
}

internal val SilphCo_3FScripts: Map<String, Script> =
    mapOf(
        "SilphCo_3F_EventScript_FloorSign" to SilphCo_3F_EventScript_FloorSign,
    )
