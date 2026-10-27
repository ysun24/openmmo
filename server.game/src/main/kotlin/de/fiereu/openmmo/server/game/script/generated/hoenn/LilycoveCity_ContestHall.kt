package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.LilycoveCity_ContestHall
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object LilycoveCity_ContestHall_EventScript_Boy1 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_ContestHall.TodayWonSmartnessContest)
}

internal object LilycoveCity_ContestHall_EventScript_Boy2 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_ContestHall.EnteredBunchOfContests)
}

internal object LilycoveCity_ContestHall_EventScript_Girl : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_ContestHall.ManWhoWonEarlierHadPokeblocks)
}

internal object LilycoveCity_ContestHall_EventScript_SmartContestAudience1 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(LilycoveCity_ContestHall.YoureBeautifulGrandpa)
}

internal object LilycoveCity_ContestHall_EventScript_Sailor : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_ContestHall.IsntThisPlaceHumongous)
}

internal object LilycoveCity_ContestHall_EventScript_BeautyStageSign : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(LilycoveCity_ContestHall.BeautyContestStage)
}

internal object LilycoveCity_ContestHall_EventScript_CuteStageSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(LilycoveCity_ContestHall.CuteContestStage)
}

internal object LilycoveCity_ContestHall_EventScript_SmartStageSign : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(LilycoveCity_ContestHall.SmartContestStage)
}

internal val LilycoveCity_ContestHallScripts: Map<String, Script> =
    mapOf(
        "LilycoveCity_ContestHall_EventScript_Boy1" to LilycoveCity_ContestHall_EventScript_Boy1,
        "LilycoveCity_ContestHall_EventScript_Boy2" to LilycoveCity_ContestHall_EventScript_Boy2,
        "LilycoveCity_ContestHall_EventScript_Girl" to LilycoveCity_ContestHall_EventScript_Girl,
        "LilycoveCity_ContestHall_EventScript_SmartContestAudience1" to
            LilycoveCity_ContestHall_EventScript_SmartContestAudience1,
        "LilycoveCity_ContestHall_EventScript_Sailor" to
            LilycoveCity_ContestHall_EventScript_Sailor,
        "LilycoveCity_ContestHall_EventScript_BeautyStageSign" to
            LilycoveCity_ContestHall_EventScript_BeautyStageSign,
        "LilycoveCity_ContestHall_EventScript_CuteStageSign" to
            LilycoveCity_ContestHall_EventScript_CuteStageSign,
        "LilycoveCity_ContestHall_EventScript_SmartStageSign" to
            LilycoveCity_ContestHall_EventScript_SmartStageSign,
    )
