package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.SilphCo_10F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SilphCo_10F_EventScript_FloorSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SilphCo_10F.FloorSign)
}

internal val SilphCo_10FScripts: Map<String, Script> =
    mapOf(
        "SilphCo_10F_EventScript_FloorSign" to SilphCo_10F_EventScript_FloorSign,
    )
