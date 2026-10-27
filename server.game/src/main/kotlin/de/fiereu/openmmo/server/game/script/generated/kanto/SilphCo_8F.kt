package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.SilphCo_8F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SilphCo_8F_EventScript_FloorSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SilphCo_8F.FloorSign)
}

internal val SilphCo_8FScripts: Map<String, Script> =
    mapOf(
        "SilphCo_8F_EventScript_FloorSign" to SilphCo_8F_EventScript_FloorSign,
    )
