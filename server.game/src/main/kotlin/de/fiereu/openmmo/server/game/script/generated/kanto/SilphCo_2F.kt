package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.SilphCo_2F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SilphCo_2F_EventScript_FloorSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SilphCo_2F.FloorSign)
}

internal val SilphCo_2FScripts: Map<String, Script> =
    mapOf(
        "SilphCo_2F_EventScript_FloorSign" to SilphCo_2F_EventScript_FloorSign,
    )
