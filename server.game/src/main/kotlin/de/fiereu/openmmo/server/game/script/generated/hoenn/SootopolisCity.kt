package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.SootopolisCity
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SootopolisCity_EventScript_GymSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SootopolisCity.GymSign)
}

internal object SootopolisCity_EventScript_CitySign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SootopolisCity.CitySign)
}

internal val SootopolisCityScripts: Map<String, Script> =
    mapOf(
        "SootopolisCity_EventScript_GymSign" to SootopolisCity_EventScript_GymSign,
        "SootopolisCity_EventScript_CitySign" to SootopolisCity_EventScript_CitySign,
    )
