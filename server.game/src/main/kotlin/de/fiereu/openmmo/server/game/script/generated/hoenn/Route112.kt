package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Route112
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route112_EventScript_Hiker : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route112.NotEasyToGetBackToLavaridge)
}

internal object Route112_EventScript_MtChimneySign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route112.MtChimneySign)
}

internal object Route112_EventScript_MtChimneyCableCarSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route112.MtChimneyCableCarSign)
}

internal object Route112_EventScript_RouteSignLavaridge : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route112.RouteSignLavaridge)
}

internal val Route112Scripts: Map<String, Script> =
    mapOf(
        "Route112_EventScript_Hiker" to Route112_EventScript_Hiker,
        "Route112_EventScript_MtChimneySign" to Route112_EventScript_MtChimneySign,
        "Route112_EventScript_MtChimneyCableCarSign" to Route112_EventScript_MtChimneyCableCarSign,
        "Route112_EventScript_RouteSignLavaridge" to Route112_EventScript_RouteSignLavaridge,
    )
