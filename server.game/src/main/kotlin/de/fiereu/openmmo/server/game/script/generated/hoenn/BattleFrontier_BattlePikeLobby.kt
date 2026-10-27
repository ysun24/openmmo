package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.BattleFrontier_BattlePikeLobby
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object BattleFrontier_BattlePikeLobby_EventScript_Hiker : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_BattlePikeLobby.OneRoomAwayFromGoal)
}

internal object BattleFrontier_BattlePikeLobby_EventScript_Twin : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_BattlePikeLobby.NeverHadToBattleTrainer)
}

internal object BattleFrontier_BattlePikeLobby_EventScript_Beauty : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_BattlePikeLobby.ThinkAbilitiesUsefulHere)
}

internal val BattleFrontier_BattlePikeLobbyScripts: Map<String, Script> =
    mapOf(
        "BattleFrontier_BattlePikeLobby_EventScript_Hiker" to
            BattleFrontier_BattlePikeLobby_EventScript_Hiker,
        "BattleFrontier_BattlePikeLobby_EventScript_Twin" to
            BattleFrontier_BattlePikeLobby_EventScript_Twin,
        "BattleFrontier_BattlePikeLobby_EventScript_Beauty" to
            BattleFrontier_BattlePikeLobby_EventScript_Beauty,
    )
