package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Route17
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route17_EventScript_RouteSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route17.RouteSign)
}

internal object Route17_EventScript_TrainerTips2 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route17.PressBToStayInPlace)
}

internal object Route17_EventScript_TrainerTips1 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route17.SameSpeciesGrowDifferentRates)
}

internal object Route17_EventScript_ItemsNotice : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route17.WatchOutForDiscardedItems)
}

internal object Route17_EventScript_BallsNotice : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route17.DontThrowGameThrowBalls)
}

internal object Route17_EventScript_CyclingRoadSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route17.CyclingRoadSign)
}

internal val Route17Scripts: Map<String, Script> =
    mapOf(
        "Route17_EventScript_RouteSign" to Route17_EventScript_RouteSign,
        "Route17_EventScript_TrainerTips2" to Route17_EventScript_TrainerTips2,
        "Route17_EventScript_TrainerTips1" to Route17_EventScript_TrainerTips1,
        "Route17_EventScript_ItemsNotice" to Route17_EventScript_ItemsNotice,
        "Route17_EventScript_BallsNotice" to Route17_EventScript_BallsNotice,
        "Route17_EventScript_CyclingRoadSign" to Route17_EventScript_CyclingRoadSign,
    )
