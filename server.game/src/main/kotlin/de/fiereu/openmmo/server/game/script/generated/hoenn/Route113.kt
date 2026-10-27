package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Route113
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route113_EventScript_NinjaBoy : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route113.FunWalkingThroughAsh)
}

internal object Route113_EventScript_Gentleman : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route113.AshCanBeFashionedIntoGlass)
}

internal object Route113_EventScript_RouteSign111 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route113.RouteSign111)
}

internal object Route113_EventScript_RouteSignFallarbor : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route113.RouteSignFallarbor)
}

internal object Route113_EventScript_TrainerTipsRegisterKeyItems : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route113.TrainerTipsRegisterKeyItems)
}

internal object Route113_EventScript_GlassWorkshopSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route113.GlassWorkshopSign)
}

internal val Route113Scripts: Map<String, Script> =
    mapOf(
        "Route113_EventScript_NinjaBoy" to Route113_EventScript_NinjaBoy,
        "Route113_EventScript_Gentleman" to Route113_EventScript_Gentleman,
        "Route113_EventScript_RouteSign111" to Route113_EventScript_RouteSign111,
        "Route113_EventScript_RouteSignFallarbor" to Route113_EventScript_RouteSignFallarbor,
        "Route113_EventScript_TrainerTipsRegisterKeyItems" to
            Route113_EventScript_TrainerTipsRegisterKeyItems,
        "Route113_EventScript_GlassWorkshopSign" to Route113_EventScript_GlassWorkshopSign,
    )
