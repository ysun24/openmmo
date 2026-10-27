package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Route25
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route25_EventScript_Man : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route25.AreYouHereAlone)
}

internal object Route25_EventScript_SeaCottageSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route25.SeaCottageSign)
}

internal val Route25Scripts: Map<String, Script> =
    mapOf(
        "Route25_EventScript_Man" to Route25_EventScript_Man,
        "Route25_EventScript_SeaCottageSign" to Route25_EventScript_SeaCottageSign,
    )
