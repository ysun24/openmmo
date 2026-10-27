package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.LilycoveCity
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object LilycoveCity_EventScript_Man3 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(LilycoveCity.ContestHallInTown)
}

internal object LilycoveCity_EventScript_RichBoy : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(LilycoveCity.ImFromKanto)
}

internal object LilycoveCity_EventScript_Man2 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(LilycoveCity.ImArtDealer)
}

internal object LilycoveCity_EventScript_ExpertM1 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(LilycoveCity.SeaRemainsForeverYoung)
}

internal object LilycoveCity_EventScript_ExpertF : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(LilycoveCity.SixtyYearsAgoHusbandProposed)
}

internal object LilycoveCity_EventScript_AquaGrunt4 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(LilycoveCity.IfWorldBecomesOurs)
}

internal object LilycoveCity_EventScript_AquaGrunt1 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(LilycoveCity.MovedLootIntoHideoutToday)
}

internal object LilycoveCity_EventScript_AquaGrunt2 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(LilycoveCity.ChanceToDoBigThings)
}

internal object LilycoveCity_EventScript_AquaGrunt3 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(LilycoveCity.DontGoNearCaveInCove)
}

internal object LilycoveCity_EventScript_Sailor1 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(LilycoveCity.HeardTowerCalledSkyPillar)
}

internal object LilycoveCity_EventScript_FatMan : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(LilycoveCity.SawTallTowerOnRoute131)
}

internal object LilycoveCity_EventScript_DepartmentStoreSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(LilycoveCity.DepartmentStoreSign)
}

internal object LilycoveCity_EventScript_CitySign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(LilycoveCity.CitySign)
}

internal object LilycoveCity_EventScript_ContestHallSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(LilycoveCity.ContestHallSign)
}

internal object LilycoveCity_EventScript_MotelSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(LilycoveCity.MotelSign)
}

internal object LilycoveCity_EventScript_TrainerFanClubSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(LilycoveCity.TrainerFanClubSign)
}

internal object LilycoveCity_EventScript_MoveDeletersHouseSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(LilycoveCity.MoveDeletersHouseSign)
}

internal val LilycoveCityScripts: Map<String, Script> =
    mapOf(
        "LilycoveCity_EventScript_Man3" to LilycoveCity_EventScript_Man3,
        "LilycoveCity_EventScript_RichBoy" to LilycoveCity_EventScript_RichBoy,
        "LilycoveCity_EventScript_Man2" to LilycoveCity_EventScript_Man2,
        "LilycoveCity_EventScript_ExpertM1" to LilycoveCity_EventScript_ExpertM1,
        "LilycoveCity_EventScript_ExpertF" to LilycoveCity_EventScript_ExpertF,
        "LilycoveCity_EventScript_AquaGrunt4" to LilycoveCity_EventScript_AquaGrunt4,
        "LilycoveCity_EventScript_AquaGrunt1" to LilycoveCity_EventScript_AquaGrunt1,
        "LilycoveCity_EventScript_AquaGrunt2" to LilycoveCity_EventScript_AquaGrunt2,
        "LilycoveCity_EventScript_AquaGrunt3" to LilycoveCity_EventScript_AquaGrunt3,
        "LilycoveCity_EventScript_Sailor1" to LilycoveCity_EventScript_Sailor1,
        "LilycoveCity_EventScript_FatMan" to LilycoveCity_EventScript_FatMan,
        "LilycoveCity_EventScript_DepartmentStoreSign" to
            LilycoveCity_EventScript_DepartmentStoreSign,
        "LilycoveCity_EventScript_CitySign" to LilycoveCity_EventScript_CitySign,
        "LilycoveCity_EventScript_ContestHallSign" to LilycoveCity_EventScript_ContestHallSign,
        "LilycoveCity_EventScript_MotelSign" to LilycoveCity_EventScript_MotelSign,
        "LilycoveCity_EventScript_TrainerFanClubSign" to
            LilycoveCity_EventScript_TrainerFanClubSign,
        "LilycoveCity_EventScript_MoveDeletersHouseSign" to
            LilycoveCity_EventScript_MoveDeletersHouseSign,
    )
