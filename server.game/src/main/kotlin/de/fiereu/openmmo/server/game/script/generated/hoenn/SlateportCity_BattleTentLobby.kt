package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.SlateportCity_BattleTentLobby
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SlateportCity_BattleTentLobby_EventScript_Man : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(SlateportCity_BattleTentLobby.IllTryUsingBugMons)
}

internal object SlateportCity_BattleTentLobby_EventScript_Girl : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(SlateportCity_BattleTentLobby.BattleEvenWithoutToughMons)
}

internal object SlateportCity_BattleTentLobby_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(SlateportCity_BattleTentLobby.NiceIfMoreSelection)
}

internal val SlateportCity_BattleTentLobbyScripts: Map<String, Script> =
    mapOf(
        "SlateportCity_BattleTentLobby_EventScript_Man" to
            SlateportCity_BattleTentLobby_EventScript_Man,
        "SlateportCity_BattleTentLobby_EventScript_Girl" to
            SlateportCity_BattleTentLobby_EventScript_Girl,
        "SlateportCity_BattleTentLobby_EventScript_Woman" to
            SlateportCity_BattleTentLobby_EventScript_Woman,
    )
