package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.FallarborTown_BattleTentLobby
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object FallarborTown_BattleTentLobby_EventScript_Hiker : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(FallarborTown_BattleTentLobby.CameToCampOut)
}

internal object FallarborTown_BattleTentLobby_EventScript_LittleBoy : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(FallarborTown_BattleTentLobby.MakeThinkImJustKid)
}

internal object FallarborTown_BattleTentLobby_EventScript_Lass : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(FallarborTown_BattleTentLobby.FallarborTentMyFavorite)
}

internal val FallarborTown_BattleTentLobbyScripts: Map<String, Script> =
    mapOf(
        "FallarborTown_BattleTentLobby_EventScript_Hiker" to
            FallarborTown_BattleTentLobby_EventScript_Hiker,
        "FallarborTown_BattleTentLobby_EventScript_LittleBoy" to
            FallarborTown_BattleTentLobby_EventScript_LittleBoy,
        "FallarborTown_BattleTentLobby_EventScript_Lass" to
            FallarborTown_BattleTentLobby_EventScript_Lass,
    )
