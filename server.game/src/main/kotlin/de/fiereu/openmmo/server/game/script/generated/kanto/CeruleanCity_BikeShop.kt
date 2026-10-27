package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.CeruleanCity_BikeShop
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object CeruleanCity_BikeShop_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(CeruleanCity_BikeShop.CityBikeGoodEnoughForMe)
}

internal val CeruleanCity_BikeShopScripts: Map<String, Script> =
    mapOf(
        "CeruleanCity_BikeShop_EventScript_Woman" to CeruleanCity_BikeShop_EventScript_Woman,
    )
