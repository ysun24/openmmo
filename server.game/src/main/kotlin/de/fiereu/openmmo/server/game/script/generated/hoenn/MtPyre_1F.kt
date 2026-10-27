package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.MtPyre_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object MtPyre_1F_EventScript_PokefanF : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(MtPyre_1F.ComeToPayRespects)
}

internal object MtPyre_1F_EventScript_Man : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(MtPyre_1F.RestingPlaceOfZigzagoon)
}

internal val MtPyre_1FScripts: Map<String, Script> =
    mapOf(
        "MtPyre_1F_EventScript_PokefanF" to MtPyre_1F_EventScript_PokefanF,
        "MtPyre_1F_EventScript_Man" to MtPyre_1F_EventScript_Man,
    )
