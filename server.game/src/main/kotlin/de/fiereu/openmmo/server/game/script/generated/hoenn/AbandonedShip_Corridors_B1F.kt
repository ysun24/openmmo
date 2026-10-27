package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.AbandonedShip_Corridors_B1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object AbandonedShip_Corridors_B1F_EventScript_TuberM : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(AbandonedShip_Corridors_B1F.YayItsAShip)
}

internal val AbandonedShip_Corridors_B1FScripts: Map<String, Script> =
    mapOf(
        "AbandonedShip_Corridors_B1F_EventScript_TuberM" to
            AbandonedShip_Corridors_B1F_EventScript_TuberM,
    )
