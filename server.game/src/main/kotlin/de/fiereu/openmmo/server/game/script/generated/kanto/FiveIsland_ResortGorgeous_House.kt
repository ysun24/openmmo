package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.FiveIsland_ResortGorgeous_House
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object FiveIsland_ResortGorgeous_House_EventScript_Butler : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(FiveIsland_ResortGorgeous_House.LadySelphySmileHasBrillianceOfSun)
}

internal val FiveIsland_ResortGorgeous_HouseScripts: Map<String, Script> =
    mapOf(
        "FiveIsland_ResortGorgeous_House_EventScript_Butler" to
            FiveIsland_ResortGorgeous_House_EventScript_Butler,
    )
