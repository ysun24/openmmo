package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.FortreeCity
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object FortreeCity_EventScript_Man : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(FortreeCity.SawGiganticPokemonInSky)
}

internal object FortreeCity_EventScript_Girl : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(FortreeCity.TreesGrowByDrinkingRainwater)
}

internal object FortreeCity_EventScript_Boy : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(FortreeCity.BugPokemonComeThroughWindow)
}

internal object FortreeCity_EventScript_OldMan : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(FortreeCity.EveryoneHealthyAndLively)
}

internal object FortreeCity_EventScript_GameboyKid : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(FortreeCity.PokemonThatEvolveWhenTraded)
}

internal object FortreeCity_EventScript_CitySign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(FortreeCity.CitySign)
}

internal object FortreeCity_EventScript_GymSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(FortreeCity.GymSign)
}

internal val FortreeCityScripts: Map<String, Script> =
    mapOf(
        "FortreeCity_EventScript_Man" to FortreeCity_EventScript_Man,
        "FortreeCity_EventScript_Girl" to FortreeCity_EventScript_Girl,
        "FortreeCity_EventScript_Boy" to FortreeCity_EventScript_Boy,
        "FortreeCity_EventScript_OldMan" to FortreeCity_EventScript_OldMan,
        "FortreeCity_EventScript_GameboyKid" to FortreeCity_EventScript_GameboyKid,
        "FortreeCity_EventScript_CitySign" to FortreeCity_EventScript_CitySign,
        "FortreeCity_EventScript_GymSign" to FortreeCity_EventScript_GymSign,
    )
