package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Route119_WeatherInstitute_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route119_WeatherInstitute_1F_EventScript_InstituteWorker2 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(Route119_WeatherInstitute_1F.WhatWereAquasUpTo)
}

internal val Route119_WeatherInstitute_1FScripts: Map<String, Script> =
    mapOf(
        "Route119_WeatherInstitute_1F_EventScript_InstituteWorker2" to
            Route119_WeatherInstitute_1F_EventScript_InstituteWorker2,
    )
