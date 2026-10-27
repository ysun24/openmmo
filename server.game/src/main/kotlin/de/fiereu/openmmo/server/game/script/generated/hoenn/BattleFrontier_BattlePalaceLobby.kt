package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.BattleFrontier_BattlePalaceLobby
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object BattleFrontier_BattlePalaceLobby_EventScript_BlackBelt : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_BattlePalaceLobby.LadyCanTellWhatMonsThink)
}

internal object BattleFrontier_BattlePalaceLobby_EventScript_Maniac : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_BattlePalaceLobby.WhatNatureFavorsChippingAway)
}

internal object BattleFrontier_BattlePalaceLobby_EventScript_Man : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_BattlePalaceLobby.NatureAndMovesKeyHere)
}

internal object BattleFrontier_BattlePalaceLobby_EventScript_Beauty : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_BattlePalaceLobby.MonDocileButTransforms)
}

internal val BattleFrontier_BattlePalaceLobbyScripts: Map<String, Script> =
    mapOf(
        "BattleFrontier_BattlePalaceLobby_EventScript_BlackBelt" to
            BattleFrontier_BattlePalaceLobby_EventScript_BlackBelt,
        "BattleFrontier_BattlePalaceLobby_EventScript_Maniac" to
            BattleFrontier_BattlePalaceLobby_EventScript_Maniac,
        "BattleFrontier_BattlePalaceLobby_EventScript_Man" to
            BattleFrontier_BattlePalaceLobby_EventScript_Man,
        "BattleFrontier_BattlePalaceLobby_EventScript_Beauty" to
            BattleFrontier_BattlePalaceLobby_EventScript_Beauty,
    )
