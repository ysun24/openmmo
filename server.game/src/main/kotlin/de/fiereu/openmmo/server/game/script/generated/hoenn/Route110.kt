package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Route110
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route110_EventScript_Boy2 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route110.WhichShouldIChoose)
}

internal object Route110_EventScript_CyclingGuy2 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route110.BikeTechniques)
}

internal object Route110_EventScript_OldWoman : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route110.WalkOnTheLowRoad)
}

internal object Route110_EventScript_CyclingGuy1 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route110.YouGotBikeFromRydel)
}

internal object Route110_EventScript_OldMan : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route110.TwoRoads)
}

internal object Route110_EventScript_CyclingGirl1 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route110.HairStreamsBehindMe)
}

internal object Route110_EventScript_Boy1 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route110.RideBikeAtFullSpeed)
}

internal object Route110_EventScript_VandalizedSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route110.AquaWasHere)
}

internal object Route110_EventScript_SeasideParkingSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route110.SeasideParkingSign)
}

internal object Route110_EventScript_CyclingRoadSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route110.CyclingRoadSign)
}

internal object Route110_EventScript_SlateportCitySign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route110.SlateportCitySign)
}

internal object Route110_EventScript_Route103Sign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route110.Route103Sign)
}

internal object Route110_EventScript_MauvilleCitySign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route110.MauvilleCitySign)
}

internal object Route110_EventScript_TrainerTipsPrlzSleep : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route110.TrainerTipsPrlzSleep)
}

internal object Route110_EventScript_TrainerTipsRegisterItems : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route110.TrainerTipsRegisterItems)
}

internal object Route110_EventScript_TrickHouseSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route110.TrickHouseSign)
}

internal val Route110Scripts: Map<String, Script> =
    mapOf(
        "Route110_EventScript_Boy2" to Route110_EventScript_Boy2,
        "Route110_EventScript_CyclingGuy2" to Route110_EventScript_CyclingGuy2,
        "Route110_EventScript_OldWoman" to Route110_EventScript_OldWoman,
        "Route110_EventScript_CyclingGuy1" to Route110_EventScript_CyclingGuy1,
        "Route110_EventScript_OldMan" to Route110_EventScript_OldMan,
        "Route110_EventScript_CyclingGirl1" to Route110_EventScript_CyclingGirl1,
        "Route110_EventScript_Boy1" to Route110_EventScript_Boy1,
        "Route110_EventScript_VandalizedSign" to Route110_EventScript_VandalizedSign,
        "Route110_EventScript_SeasideParkingSign" to Route110_EventScript_SeasideParkingSign,
        "Route110_EventScript_CyclingRoadSign" to Route110_EventScript_CyclingRoadSign,
        "Route110_EventScript_SlateportCitySign" to Route110_EventScript_SlateportCitySign,
        "Route110_EventScript_Route103Sign" to Route110_EventScript_Route103Sign,
        "Route110_EventScript_MauvilleCitySign" to Route110_EventScript_MauvilleCitySign,
        "Route110_EventScript_TrainerTipsPrlzSleep" to Route110_EventScript_TrainerTipsPrlzSleep,
        "Route110_EventScript_TrainerTipsRegisterItems" to
            Route110_EventScript_TrainerTipsRegisterItems,
        "Route110_EventScript_TrickHouseSign" to Route110_EventScript_TrickHouseSign,
    )
