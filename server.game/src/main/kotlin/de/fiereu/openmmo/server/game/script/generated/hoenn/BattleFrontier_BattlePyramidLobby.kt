package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.BattleFrontier_BattlePyramidLobby
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object BattleFrontier_BattlePyramidLobby_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_BattlePyramidLobby.TrainersNoticeRunning)
}

internal object BattleFrontier_BattlePyramidLobby_EventScript_FatMan : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_BattlePyramidLobby.LostLotOfItems)
}

internal val BattleFrontier_BattlePyramidLobbyScripts: Map<String, Script> =
    mapOf(
        "BattleFrontier_BattlePyramidLobby_EventScript_Woman" to
            BattleFrontier_BattlePyramidLobby_EventScript_Woman,
        "BattleFrontier_BattlePyramidLobby_EventScript_FatMan" to
            BattleFrontier_BattlePyramidLobby_EventScript_FatMan,
    )
