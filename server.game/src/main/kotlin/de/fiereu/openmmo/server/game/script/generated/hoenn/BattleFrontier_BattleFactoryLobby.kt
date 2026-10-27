package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.BattleFrontier_BattleFactoryLobby
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object BattleFrontier_BattleFactoryLobby_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_BattleFactoryLobby.NeedKnowledgeOfMonsMoves)
}

internal object BattleFrontier_BattleFactoryLobby_EventScript_Camper : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_BattleFactoryLobby.SwappedForWeakMon)
}

internal object BattleFrontier_BattleFactoryLobby_EventScript_Picnicker : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_BattleFactoryLobby.NeedToCheckOpponentsMons)
}

internal object BattleFrontier_BattleFactoryLobby_EventScript_FatMan : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_BattleFactoryLobby.CantFigureOutStaffHints)
}

internal val BattleFrontier_BattleFactoryLobbyScripts: Map<String, Script> =
    mapOf(
        "BattleFrontier_BattleFactoryLobby_EventScript_Woman" to
            BattleFrontier_BattleFactoryLobby_EventScript_Woman,
        "BattleFrontier_BattleFactoryLobby_EventScript_Camper" to
            BattleFrontier_BattleFactoryLobby_EventScript_Camper,
        "BattleFrontier_BattleFactoryLobby_EventScript_Picnicker" to
            BattleFrontier_BattleFactoryLobby_EventScript_Picnicker,
        "BattleFrontier_BattleFactoryLobby_EventScript_FatMan" to
            BattleFrontier_BattleFactoryLobby_EventScript_FatMan,
    )
