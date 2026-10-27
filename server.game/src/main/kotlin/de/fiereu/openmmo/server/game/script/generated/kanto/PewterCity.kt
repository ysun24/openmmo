package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.PewterCity
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object PewterCity_EventScript_Lass : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(PewterCity.ClefairyCameFromMoon)
}

internal object PewterCity_EventScript_MuseumSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(PewterCity.MuseumOfScience)
}

internal object PewterCity_EventScript_PoliceNotice : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(PewterCity.CallPoliceIfInfoOnThieves)
}

internal object PewterCity_EventScript_TrainerTips : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(PewterCity.TrainerTipsEarningEXP)
}

internal object PewterCity_EventScript_CitySign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(PewterCity.CitySign)
}

internal val PewterCityScripts: Map<String, Script> =
    mapOf(
        "PewterCity_EventScript_Lass" to PewterCity_EventScript_Lass,
        "PewterCity_EventScript_MuseumSign" to PewterCity_EventScript_MuseumSign,
        "PewterCity_EventScript_PoliceNotice" to PewterCity_EventScript_PoliceNotice,
        "PewterCity_EventScript_TrainerTips" to PewterCity_EventScript_TrainerTips,
        "PewterCity_EventScript_CitySign" to PewterCity_EventScript_CitySign,
    )
