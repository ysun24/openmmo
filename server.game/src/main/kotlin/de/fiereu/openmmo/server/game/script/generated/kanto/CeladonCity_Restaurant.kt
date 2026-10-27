package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.CeladonCity_Restaurant
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object CeladonCity_Restaurant_EventScript_Chef : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(CeladonCity_Restaurant.TakingBreakRightNow)
}

internal object CeladonCity_Restaurant_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(CeladonCity_Restaurant.OftenGoToDrugstore)
}

internal object CeladonCity_Restaurant_EventScript_FatMan : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(CeladonCity_Restaurant.ManLostItAllAtSlots)
}

internal object CeladonCity_Restaurant_EventScript_WorkerM : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(CeladonCity_Restaurant.PsstBasementUnderGameCorner)
}

internal val CeladonCity_RestaurantScripts: Map<String, Script> =
    mapOf(
        "CeladonCity_Restaurant_EventScript_Chef" to CeladonCity_Restaurant_EventScript_Chef,
        "CeladonCity_Restaurant_EventScript_Woman" to CeladonCity_Restaurant_EventScript_Woman,
        "CeladonCity_Restaurant_EventScript_FatMan" to CeladonCity_Restaurant_EventScript_FatMan,
        "CeladonCity_Restaurant_EventScript_WorkerM" to CeladonCity_Restaurant_EventScript_WorkerM,
    )
