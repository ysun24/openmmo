package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.AbandonedShip_Rooms_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object AbandonedShip_Rooms_1F_EventScript_Gentleman : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(AbandonedShip_Rooms_1F.TakingALookAround)
}

internal val AbandonedShip_Rooms_1FScripts: Map<String, Script> =
    mapOf(
        "AbandonedShip_Rooms_1F_EventScript_Gentleman" to
            AbandonedShip_Rooms_1F_EventScript_Gentleman,
    )
