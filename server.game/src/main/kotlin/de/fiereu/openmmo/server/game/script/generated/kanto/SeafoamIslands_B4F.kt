package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.SeafoamIslands_B4F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SeafoamIslands_B4F_EventScript_FastCurrentSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SeafoamIslands_B4F.DangerFastCurrent)
}

internal object SeafoamIslands_B4F_EventScript_BoulderHintSign : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(SeafoamIslands_B4F.BouldersMightChangeWaterFlow)
}

internal val SeafoamIslands_B4FScripts: Map<String, Script> =
    mapOf(
        "SeafoamIslands_B4F_EventScript_FastCurrentSign" to
            SeafoamIslands_B4F_EventScript_FastCurrentSign,
        "SeafoamIslands_B4F_EventScript_BoulderHintSign" to
            SeafoamIslands_B4F_EventScript_BoulderHintSign,
    )
