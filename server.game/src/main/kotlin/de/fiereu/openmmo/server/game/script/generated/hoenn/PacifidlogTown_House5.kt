package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.PacifidlogTown_House5
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object PacifidlogTown_House5_EventScript_Gentleman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(PacifidlogTown_House5.MirageIslandAppearDependingOnWeather)
}

internal val PacifidlogTown_House5Scripts: Map<String, Script> =
    mapOf(
        "PacifidlogTown_House5_EventScript_Gentleman" to
            PacifidlogTown_House5_EventScript_Gentleman,
    )
