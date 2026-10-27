package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.BattleFrontier_BattleArenaLobby
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object BattleFrontier_BattleArenaLobby_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_BattleArenaLobby.OrderOfMonsImportant)
}

internal object BattleFrontier_BattleArenaLobby_EventScript_Man : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_BattleArenaLobby.LandingHitsWorked)
}

internal object BattleFrontier_BattleArenaLobby_EventScript_Camper : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_BattleArenaLobby.MatchWasDeclaredDraw)
}

internal object BattleFrontier_BattleArenaLobby_EventScript_Youngster : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_BattleArenaLobby.BadIdeaToNotAttack)
}

internal val BattleFrontier_BattleArenaLobbyScripts: Map<String, Script> =
    mapOf(
        "BattleFrontier_BattleArenaLobby_EventScript_Woman" to
            BattleFrontier_BattleArenaLobby_EventScript_Woman,
        "BattleFrontier_BattleArenaLobby_EventScript_Man" to
            BattleFrontier_BattleArenaLobby_EventScript_Man,
        "BattleFrontier_BattleArenaLobby_EventScript_Camper" to
            BattleFrontier_BattleArenaLobby_EventScript_Camper,
        "BattleFrontier_BattleArenaLobby_EventScript_Youngster" to
            BattleFrontier_BattleArenaLobby_EventScript_Youngster,
    )
