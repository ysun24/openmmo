package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Route106
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route106_EventScript_TrainerTipsSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route106.TrainerTips)
}

internal val Route106Scripts: Map<String, Script> =
    mapOf(
        "Route106_EventScript_TrainerTipsSign" to Route106_EventScript_TrainerTipsSign,
    )
