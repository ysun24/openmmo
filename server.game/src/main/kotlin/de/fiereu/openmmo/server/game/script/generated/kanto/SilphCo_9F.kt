package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.SilphCo_9F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SilphCo_9F_EventScript_FloorSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SilphCo_9F.FloorSign)
}

internal val SilphCo_9FScripts: Map<String, Script> =
    mapOf(
        "SilphCo_9F_EventScript_FloorSign" to SilphCo_9F_EventScript_FloorSign,
    )
