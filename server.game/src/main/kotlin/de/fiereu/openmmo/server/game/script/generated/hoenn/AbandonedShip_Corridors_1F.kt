package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.AbandonedShip_Corridors_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object AbandonedShip_Corridors_1F_EventScript_Youngster : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(AbandonedShip_Corridors_1F.IsntItFunHere)
}

internal val AbandonedShip_Corridors_1FScripts: Map<String, Script> =
    mapOf(
        "AbandonedShip_Corridors_1F_EventScript_Youngster" to
            AbandonedShip_Corridors_1F_EventScript_Youngster,
    )
