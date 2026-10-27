package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.FortreeCity_House4
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object FortreeCity_House4_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(FortreeCity_House4.BringsWorldCloserTogether)
}

internal val FortreeCity_House4Scripts: Map<String, Script> =
    mapOf(
        "FortreeCity_House4_EventScript_Woman" to FortreeCity_House4_EventScript_Woman,
    )
