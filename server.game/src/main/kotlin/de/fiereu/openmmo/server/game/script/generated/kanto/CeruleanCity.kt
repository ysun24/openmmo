package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.CeruleanCity
import de.fiereu.openmmo.dialog.generated.kanto.CeruleanCity_BikeShop
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object CeruleanCity_EventScript_BaldingMan : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(CeruleanCity.PokemonEncyclopediaAmusing)
}

internal object CeruleanCity_EventScript_Youngster : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(CeruleanCity.TrainerLifeIsToughIsntIt)
}

internal object CeruleanCity_EventScript_CeruleanCaveGuard : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(CeruleanCity.ThisIsCeruleanCave)
}

internal object CeruleanCity_EventScript_CitySign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(CeruleanCity.CitySign)
}

internal object CeruleanCity_EventScript_BikeShopSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(CeruleanCity.BikeShopSign)
}

internal object CeruleanCity_EventScript_TrainerTips : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(CeruleanCity.TrainerTipsHeldItems)
}

internal object CeruleanCity_BikeShop_EventScript_Bicycle : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(CeruleanCity_BikeShop.ShinyNewBicycle)
}

internal val CeruleanCityScripts: Map<String, Script> =
    mapOf(
        "CeruleanCity_EventScript_BaldingMan" to CeruleanCity_EventScript_BaldingMan,
        "CeruleanCity_EventScript_Youngster" to CeruleanCity_EventScript_Youngster,
        "CeruleanCity_EventScript_CeruleanCaveGuard" to CeruleanCity_EventScript_CeruleanCaveGuard,
        "CeruleanCity_EventScript_CitySign" to CeruleanCity_EventScript_CitySign,
        "CeruleanCity_EventScript_BikeShopSign" to CeruleanCity_EventScript_BikeShopSign,
        "CeruleanCity_EventScript_TrainerTips" to CeruleanCity_EventScript_TrainerTips,
        "CeruleanCity_BikeShop_EventScript_Bicycle" to CeruleanCity_BikeShop_EventScript_Bicycle,
    )
