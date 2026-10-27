package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.LilycoveCity_House1
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object LilycoveCity_House1_EventScript_ExpertM : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_House1.PokemonPartnersNotTools)
}

internal val LilycoveCity_House1Scripts: Map<String, Script> =
    mapOf(
        "LilycoveCity_House1_EventScript_ExpertM" to LilycoveCity_House1_EventScript_ExpertM,
    )
