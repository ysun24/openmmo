package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Route15_WestEntrance_2F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route15_WestEntrance_2F_EventScript_RightBinoculars : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(Route15_WestEntrance_2F.SmallIslandOnHorizon)
}

internal val Route15_WestEntrance_2FScripts: Map<String, Script> =
    mapOf(
        "Route15_WestEntrance_2F_EventScript_RightBinoculars" to
            Route15_WestEntrance_2F_EventScript_RightBinoculars,
    )
