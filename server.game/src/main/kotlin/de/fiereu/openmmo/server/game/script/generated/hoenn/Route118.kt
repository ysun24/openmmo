package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Route118
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route118_EventScript_Girl : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route118.CanCrossRiversWithSurf)
}

internal object Route118_EventScript_RouteSignMauville : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route118.RouteSignMauville)
}

internal object Route118_EventScript_RouteSign119 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route118.RouteSign119)
}

internal val Route118Scripts: Map<String, Script> =
    mapOf(
        "Route118_EventScript_Girl" to Route118_EventScript_Girl,
        "Route118_EventScript_RouteSignMauville" to Route118_EventScript_RouteSignMauville,
        "Route118_EventScript_RouteSign119" to Route118_EventScript_RouteSign119,
    )
