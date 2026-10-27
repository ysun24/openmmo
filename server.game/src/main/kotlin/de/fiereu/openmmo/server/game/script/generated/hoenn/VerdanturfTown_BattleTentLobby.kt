package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.VerdanturfTown_BattleTentLobby
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object VerdanturfTown_BattleTentLobby_EventScript_Boy1 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(VerdanturfTown_BattleTentLobby.TaughtWhatKindsOfMoves)
}

internal object VerdanturfTown_BattleTentLobby_EventScript_Boy2 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(VerdanturfTown_BattleTentLobby.MonsReluctantToUseDislikedMoves)
}

internal object VerdanturfTown_BattleTentLobby_EventScript_LittleBoy : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(VerdanturfTown_BattleTentLobby.GentleMonsScaryIfAngry)
}

internal val VerdanturfTown_BattleTentLobbyScripts: Map<String, Script> =
    mapOf(
        "VerdanturfTown_BattleTentLobby_EventScript_Boy1" to
            VerdanturfTown_BattleTentLobby_EventScript_Boy1,
        "VerdanturfTown_BattleTentLobby_EventScript_Boy2" to
            VerdanturfTown_BattleTentLobby_EventScript_Boy2,
        "VerdanturfTown_BattleTentLobby_EventScript_LittleBoy" to
            VerdanturfTown_BattleTentLobby_EventScript_LittleBoy,
    )
