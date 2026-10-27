package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Route13
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route13_EventScript_RouteSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route13.RouteSign)
}

internal object Route13_EventScript_TrainerTips2 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route13.SelectToSwitchItems)
}

internal object Route13_EventScript_TrainerTips1 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route13.LookToLeftOfThatPost)
}

internal val Route13Scripts: Map<String, Script> =
    mapOf(
        "Route13_EventScript_RouteSign" to Route13_EventScript_RouteSign,
        "Route13_EventScript_TrainerTips2" to Route13_EventScript_TrainerTips2,
        "Route13_EventScript_TrainerTips1" to Route13_EventScript_TrainerTips1,
    )
