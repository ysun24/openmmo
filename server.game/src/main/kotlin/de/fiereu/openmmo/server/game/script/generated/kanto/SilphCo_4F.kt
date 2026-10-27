package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.SilphCo_4F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SilphCo_4F_EventScript_FloorSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SilphCo_4F.FloorSign)
}

internal val SilphCo_4FScripts: Map<String, Script> =
    mapOf(
        "SilphCo_4F_EventScript_FloorSign" to SilphCo_4F_EventScript_FloorSign,
    )
