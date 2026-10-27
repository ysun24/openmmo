package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.MauvilleCity
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object MauvilleCity_EventScript_Boy : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(MauvilleCity.NurseHurtMonBackToHealth)
}

internal object MauvilleCity_EventScript_RichBoy : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(MauvilleCity.PokemonCanJumpYouOnBike)
}

internal object MauvilleCity_EventScript_Maniac : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(MauvilleCity.AllSortsOfPeopleComeThrough)
}

internal object MauvilleCity_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(MauvilleCity.RydelVeryGenerous)
}

internal object MauvilleCity_EventScript_GymSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(MauvilleCity.GymSign)
}

internal object MauvilleCity_EventScript_CitySign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(MauvilleCity.CitySign)
}

internal object MauvilleCity_EventScript_BikeShopSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(MauvilleCity.BikeShopSign)
}

internal object MauvilleCity_EventScript_GameCornerSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(MauvilleCity.GameCornerSign)
}

internal val MauvilleCityScripts: Map<String, Script> =
    mapOf(
        "MauvilleCity_EventScript_Boy" to MauvilleCity_EventScript_Boy,
        "MauvilleCity_EventScript_RichBoy" to MauvilleCity_EventScript_RichBoy,
        "MauvilleCity_EventScript_Maniac" to MauvilleCity_EventScript_Maniac,
        "MauvilleCity_EventScript_Woman" to MauvilleCity_EventScript_Woman,
        "MauvilleCity_EventScript_GymSign" to MauvilleCity_EventScript_GymSign,
        "MauvilleCity_EventScript_CitySign" to MauvilleCity_EventScript_CitySign,
        "MauvilleCity_EventScript_BikeShopSign" to MauvilleCity_EventScript_BikeShopSign,
        "MauvilleCity_EventScript_GameCornerSign" to MauvilleCity_EventScript_GameCornerSign,
    )
