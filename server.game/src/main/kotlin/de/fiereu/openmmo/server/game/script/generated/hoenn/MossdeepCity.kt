package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.MossdeepCity
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object MossdeepCity_EventScript_ExpertM : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(MossdeepCity.LifeNeedsSeaToLive)
}

internal object MossdeepCity_EventScript_NinjaBoy : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(MossdeepCity.WailmerWatching)
}

internal object MossdeepCity_EventScript_Girl : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(MossdeepCity.NiceIfWorldCoveredByFlowers)
}

internal object MossdeepCity_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(MossdeepCity.SpecialSpaceCenterRock)
}

internal object MossdeepCity_EventScript_BlackBelt : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(MossdeepCity.SootopolisNewGymLeader)
}

internal object MossdeepCity_EventScript_CitySign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(MossdeepCity.CitySign)
}

internal object MossdeepCity_EventScript_GymSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(MossdeepCity.GymSign)
}

internal object MossdeepCity_EventScript_SpaceCenterSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(MossdeepCity.SpaceCenterSign)
}

internal object MossdeepCity_EventScript_WhiteRock : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(MossdeepCity.ItsAWhiteRock)
}

internal val MossdeepCityScripts: Map<String, Script> =
    mapOf(
        "MossdeepCity_EventScript_ExpertM" to MossdeepCity_EventScript_ExpertM,
        "MossdeepCity_EventScript_NinjaBoy" to MossdeepCity_EventScript_NinjaBoy,
        "MossdeepCity_EventScript_Girl" to MossdeepCity_EventScript_Girl,
        "MossdeepCity_EventScript_Woman" to MossdeepCity_EventScript_Woman,
        "MossdeepCity_EventScript_BlackBelt" to MossdeepCity_EventScript_BlackBelt,
        "MossdeepCity_EventScript_CitySign" to MossdeepCity_EventScript_CitySign,
        "MossdeepCity_EventScript_GymSign" to MossdeepCity_EventScript_GymSign,
        "MossdeepCity_EventScript_SpaceCenterSign" to MossdeepCity_EventScript_SpaceCenterSign,
        "MossdeepCity_EventScript_WhiteRock" to MossdeepCity_EventScript_WhiteRock,
    )
