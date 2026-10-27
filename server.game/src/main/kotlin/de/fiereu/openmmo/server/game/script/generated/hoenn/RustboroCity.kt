package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.RustboroCity
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext
import de.fiereu.openmmo.story.generated.hoenn.HoennFlags

internal object RustboroCity_OnTransition : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.setFlag(HoennFlags.FLAG_VISITED_RUSTBORO_CITY)
  }
}

internal object RustboroCity_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(RustboroCity.GymLeaderIsntEasyWithFire)
}

internal object RustboroCity_EventScript_NinjaBoy : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(RustboroCity.CatchRarePokemonIfIGoToSchool)
}

internal object RustboroCity_EventScript_Twin : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(RustboroCity.WowYouHavePokemon)
}

internal object RustboroCity_EventScript_DevonEmployee2 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(RustboroCity.YoureNewAroundHere)
}

internal object RustboroCity_EventScript_Man2 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(RustboroCity.TradePokemonGrowFast)
}

internal object RustboroCity_EventScript_Boy1 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(RustboroCity.YouCanHave2On2Battle)
}

internal object RustboroCity_EventScript_GymSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(RustboroCity.GymSign)
}

internal object RustboroCity_EventScript_TrainersSchoolSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(RustboroCity.TrainersSchoolSign)
}

internal object RustboroCity_EventScript_CitySign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(RustboroCity.CitySign)
}

internal object RustboroCity_EventScript_DevonCorpSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(RustboroCity.DevonCorpSign)
}

internal object RustboroCity_EventScript_TunnelSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(RustboroCity.TunnelNearingCompletion)
}

internal object RustboroCity_EventScript_CuttersHouseSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(RustboroCity.CuttersHouse)
}

internal val RustboroCityScripts: Map<String, Script> =
    mapOf(
        "RustboroCity_OnTransition" to RustboroCity_OnTransition,
        "RustboroCity_EventScript_Woman" to RustboroCity_EventScript_Woman,
        "RustboroCity_EventScript_NinjaBoy" to RustboroCity_EventScript_NinjaBoy,
        "RustboroCity_EventScript_Twin" to RustboroCity_EventScript_Twin,
        "RustboroCity_EventScript_DevonEmployee2" to RustboroCity_EventScript_DevonEmployee2,
        "RustboroCity_EventScript_Man2" to RustboroCity_EventScript_Man2,
        "RustboroCity_EventScript_Boy1" to RustboroCity_EventScript_Boy1,
        "RustboroCity_EventScript_GymSign" to RustboroCity_EventScript_GymSign,
        "RustboroCity_EventScript_TrainersSchoolSign" to
            RustboroCity_EventScript_TrainersSchoolSign,
        "RustboroCity_EventScript_CitySign" to RustboroCity_EventScript_CitySign,
        "RustboroCity_EventScript_DevonCorpSign" to RustboroCity_EventScript_DevonCorpSign,
        "RustboroCity_EventScript_TunnelSign" to RustboroCity_EventScript_TunnelSign,
        "RustboroCity_EventScript_CuttersHouseSign" to RustboroCity_EventScript_CuttersHouseSign,
    )
