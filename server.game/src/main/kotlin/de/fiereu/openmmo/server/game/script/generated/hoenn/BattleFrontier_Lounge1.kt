package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.BattleFrontier_Lounge1
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object BattleFrontier_Lounge1_EventScript_Boy1 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_Lounge1.SaidMyMonIsOutstanding)
}

internal object BattleFrontier_Lounge1_EventScript_Boy2 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_Lounge1.DidntDoAnythingSpecialRaisingIt)
}

internal val BattleFrontier_Lounge1Scripts: Map<String, Script> =
    mapOf(
        "BattleFrontier_Lounge1_EventScript_Boy1" to BattleFrontier_Lounge1_EventScript_Boy1,
        "BattleFrontier_Lounge1_EventScript_Boy2" to BattleFrontier_Lounge1_EventScript_Boy2,
    )
