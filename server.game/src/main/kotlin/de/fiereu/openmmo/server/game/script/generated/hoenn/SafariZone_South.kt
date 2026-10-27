package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.SafariZone_South
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SafariZone_South_EventScript_Boy : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(SafariZone_South.Boy)
}

internal object SafariZone_South_EventScript_Man : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(SafariZone_South.Man)
}

internal object SafariZone_South_EventScript_Youngster : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(SafariZone_South.Youngster)
}

internal object SafariZone_South_EventScript_ConstructionWorker1 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(SafariZone_South.AreaOffLimits1)
}

internal object SafariZone_South_EventScript_ConstructionWorker2 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(SafariZone_South.AreaOffLimits2)
}

internal val SafariZone_SouthScripts: Map<String, Script> =
    mapOf(
        "SafariZone_South_EventScript_Boy" to SafariZone_South_EventScript_Boy,
        "SafariZone_South_EventScript_Man" to SafariZone_South_EventScript_Man,
        "SafariZone_South_EventScript_Youngster" to SafariZone_South_EventScript_Youngster,
        "SafariZone_South_EventScript_ConstructionWorker1" to
            SafariZone_South_EventScript_ConstructionWorker1,
        "SafariZone_South_EventScript_ConstructionWorker2" to
            SafariZone_South_EventScript_ConstructionWorker2,
    )
