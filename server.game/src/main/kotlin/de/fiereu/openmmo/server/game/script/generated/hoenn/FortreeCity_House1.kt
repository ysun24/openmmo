package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.FortreeCity_House1
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object FortreeCity_House1_EventScript_ExpertF : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(FortreeCity_House1.TradingMemoriesWithOthers)
}

internal val FortreeCity_House1Scripts: Map<String, Script> =
    mapOf(
        "FortreeCity_House1_EventScript_ExpertF" to FortreeCity_House1_EventScript_ExpertF,
    )
