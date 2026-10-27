package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Route121
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route121_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route121.AheadLoomsMtPyre)
}

internal object Route121_EventScript_MtPyrePierSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route121.MtPyrePierSign)
}

internal object Route121_EventScript_SafariZoneSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route121.SafariZoneSign)
}

internal val Route121Scripts: Map<String, Script> =
    mapOf(
        "Route121_EventScript_Woman" to Route121_EventScript_Woman,
        "Route121_EventScript_MtPyrePierSign" to Route121_EventScript_MtPyrePierSign,
        "Route121_EventScript_SafariZoneSign" to Route121_EventScript_SafariZoneSign,
    )
