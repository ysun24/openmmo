package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Route4
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route4_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route4.TrippedOverGeodude)
}

internal object Route4_EventScript_MtMoonSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route4.MtMoonEntrance)
}

internal object Route4_EventScript_RouteSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route4.RouteSign)
}

internal val Route4Scripts: Map<String, Script> =
    mapOf(
        "Route4_EventScript_Woman" to Route4_EventScript_Woman,
        "Route4_EventScript_MtMoonSign" to Route4_EventScript_MtMoonSign,
        "Route4_EventScript_RouteSign" to Route4_EventScript_RouteSign,
    )
