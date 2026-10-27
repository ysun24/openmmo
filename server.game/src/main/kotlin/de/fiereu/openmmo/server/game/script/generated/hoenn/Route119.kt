package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Route119
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route119_EventScript_Boy1 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route119.ThoughtFlyByCatchingBirdMons)
}

internal object Route119_EventScript_CyclingTriathleteM : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route119.TallGrassSnaresBikeTires)
}

internal object Route119_EventScript_Boy2 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route119.CanYourMonMakeSecretBase)
}

internal object Route119_EventScript_WeatherInstituteSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route119.WeatherInstitute)
}

internal object Route119_EventScript_RouteSignFortree : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route119.RouteSignFortree)
}

internal object Route119_EventScript_TrainerTipsDecoration : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route119.TrainerTipsDecoration)
}

internal val Route119Scripts: Map<String, Script> =
    mapOf(
        "Route119_EventScript_Boy1" to Route119_EventScript_Boy1,
        "Route119_EventScript_CyclingTriathleteM" to Route119_EventScript_CyclingTriathleteM,
        "Route119_EventScript_Boy2" to Route119_EventScript_Boy2,
        "Route119_EventScript_WeatherInstituteSign" to Route119_EventScript_WeatherInstituteSign,
        "Route119_EventScript_RouteSignFortree" to Route119_EventScript_RouteSignFortree,
        "Route119_EventScript_TrainerTipsDecoration" to Route119_EventScript_TrainerTipsDecoration,
    )
