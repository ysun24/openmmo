package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.BattleFrontier_Lounge3
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object BattleFrontier_Lounge3_EventScript_Man : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_Lounge3.ShouldBeTakingChallenges)
}

internal val BattleFrontier_Lounge3Scripts: Map<String, Script> =
    mapOf(
        "BattleFrontier_Lounge3_EventScript_Man" to BattleFrontier_Lounge3_EventScript_Man,
    )
