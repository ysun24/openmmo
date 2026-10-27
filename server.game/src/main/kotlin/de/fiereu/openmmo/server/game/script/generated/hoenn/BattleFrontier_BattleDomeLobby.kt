package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.BattleFrontier_BattleDomeLobby
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object BattleFrontier_BattleDomeLobby_EventScript_Man : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_BattleDomeLobby.NeedToCheckOpponentCarefully)
}

internal object BattleFrontier_BattleDomeLobby_EventScript_Lass : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_BattleDomeLobby.WinnersGainReputation)
}

internal object BattleFrontier_BattleDomeLobby_EventScript_FatMan : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_BattleDomeLobby.TrashedInFirstRound)
}

internal val BattleFrontier_BattleDomeLobbyScripts: Map<String, Script> =
    mapOf(
        "BattleFrontier_BattleDomeLobby_EventScript_Man" to
            BattleFrontier_BattleDomeLobby_EventScript_Man,
        "BattleFrontier_BattleDomeLobby_EventScript_Lass" to
            BattleFrontier_BattleDomeLobby_EventScript_Lass,
        "BattleFrontier_BattleDomeLobby_EventScript_FatMan" to
            BattleFrontier_BattleDomeLobby_EventScript_FatMan,
    )
