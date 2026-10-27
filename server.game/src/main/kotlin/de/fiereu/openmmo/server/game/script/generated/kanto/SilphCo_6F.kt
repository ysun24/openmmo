package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.SilphCo_6F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SilphCo_6F_EventScript_FloorSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SilphCo_6F.FloorSign)
}

internal val SilphCo_6FScripts: Map<String, Script> =
    mapOf(
        "SilphCo_6F_EventScript_FloorSign" to SilphCo_6F_EventScript_FloorSign,
    )
