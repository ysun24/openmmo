package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.BattleFrontier_Lounge7
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object BattleFrontier_Lounge7_EventScript_Sailor : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_Lounge7.ThinkLadiesDontGetAlong)
}

internal object BattleFrontier_Lounge7_EventScript_Gentleman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_Lounge7.LadiesWereStrongAndBeautiful)
}

internal val BattleFrontier_Lounge7Scripts: Map<String, Script> =
    mapOf(
        "BattleFrontier_Lounge7_EventScript_Sailor" to BattleFrontier_Lounge7_EventScript_Sailor,
        "BattleFrontier_Lounge7_EventScript_Gentleman" to
            BattleFrontier_Lounge7_EventScript_Gentleman,
    )
