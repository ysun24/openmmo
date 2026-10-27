package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.BattleFrontier_BattleTowerLobby
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object BattleFrontier_BattleTowerLobby_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_BattleTowerLobby.WinsInRowRecorded)
}

internal object BattleFrontier_BattleTowerLobby_EventScript_Boy : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_BattleTowerLobby.CanLeaveUntilLossOrSevenWins)
}

internal val BattleFrontier_BattleTowerLobbyScripts: Map<String, Script> =
    mapOf(
        "BattleFrontier_BattleTowerLobby_EventScript_Woman" to
            BattleFrontier_BattleTowerLobby_EventScript_Woman,
        "BattleFrontier_BattleTowerLobby_EventScript_Boy" to
            BattleFrontier_BattleTowerLobby_EventScript_Boy,
    )
