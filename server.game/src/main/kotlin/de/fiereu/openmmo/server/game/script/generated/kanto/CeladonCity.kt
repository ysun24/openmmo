package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.CeladonCity
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object CeladonCity_EventScript_RocketGrunt1 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(CeladonCity.KeepOutOfTeamRocketsWay)
}

internal object CeladonCity_EventScript_LittleGirl : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(CeladonCity.GotMyKoffingInCinnabar)
}

internal object CeladonCity_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(CeladonCity.GameCornerIsBadForCitysImage)
}

internal object CeladonCity_EventScript_OldMan2 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(CeladonCity.BlewItAllAtSlots)
}

internal object CeladonCity_EventScript_RocketGrunt2 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(CeladonCity.GetLostOrIllPunchYou)
}

internal object CeladonCity_EventScript_OldMan1 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(CeladonCity.GymIsGreatFullOfWomen)
}

internal object CeladonCity_EventScript_Boy : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(CeladonCity.ScaldedTongueOnTea)
}

internal object CeladonCity_EventScript_SilphCoScientist : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(CeladonCity.SomeoneStoleSilphScope)
}

internal object CeladonCity_EventScript_TrainerTips2 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(CeladonCity.GuardSpecProtectsFromStatus)
}

internal object CeladonCity_EventScript_PrizeExchangeSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(CeladonCity.PrizeExchangeSign)
}

internal object CeladonCity_EventScript_GameCornerSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(CeladonCity.GameCornerSign)
}

internal object CeladonCity_EventScript_CitySign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(CeladonCity.CitySign)
}

internal object CeladonCity_EventScript_MansionSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(CeladonCity.MansionSign)
}

internal object CeladonCity_EventScript_TrainerTips1 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(CeladonCity.ExplainXAccuracyDireHit)
}

internal object CeladonCity_EventScript_DeptStoreSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(CeladonCity.DeptStoreSign)
}

internal val CeladonCityScripts: Map<String, Script> =
    mapOf(
        "CeladonCity_EventScript_RocketGrunt1" to CeladonCity_EventScript_RocketGrunt1,
        "CeladonCity_EventScript_LittleGirl" to CeladonCity_EventScript_LittleGirl,
        "CeladonCity_EventScript_Woman" to CeladonCity_EventScript_Woman,
        "CeladonCity_EventScript_OldMan2" to CeladonCity_EventScript_OldMan2,
        "CeladonCity_EventScript_RocketGrunt2" to CeladonCity_EventScript_RocketGrunt2,
        "CeladonCity_EventScript_OldMan1" to CeladonCity_EventScript_OldMan1,
        "CeladonCity_EventScript_Boy" to CeladonCity_EventScript_Boy,
        "CeladonCity_EventScript_SilphCoScientist" to CeladonCity_EventScript_SilphCoScientist,
        "CeladonCity_EventScript_TrainerTips2" to CeladonCity_EventScript_TrainerTips2,
        "CeladonCity_EventScript_PrizeExchangeSign" to CeladonCity_EventScript_PrizeExchangeSign,
        "CeladonCity_EventScript_GameCornerSign" to CeladonCity_EventScript_GameCornerSign,
        "CeladonCity_EventScript_CitySign" to CeladonCity_EventScript_CitySign,
        "CeladonCity_EventScript_MansionSign" to CeladonCity_EventScript_MansionSign,
        "CeladonCity_EventScript_TrainerTips1" to CeladonCity_EventScript_TrainerTips1,
        "CeladonCity_EventScript_DeptStoreSign" to CeladonCity_EventScript_DeptStoreSign,
    )
