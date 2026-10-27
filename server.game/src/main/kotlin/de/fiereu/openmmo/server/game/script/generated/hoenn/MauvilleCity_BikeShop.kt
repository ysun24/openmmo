package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.MauvilleCity_BikeShop
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object MauvilleCity_BikeShop_EventScript_Assistant : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(MauvilleCity_BikeShop.HandbooksAreInBack)
}

internal val MauvilleCity_BikeShopScripts: Map<String, Script> =
    mapOf(
        "MauvilleCity_BikeShop_EventScript_Assistant" to
            MauvilleCity_BikeShop_EventScript_Assistant,
    )
