package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.MtPyre_2F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object MtPyre_2F_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(MtPyre_2F.MemoriesOfSkitty)
}

internal object MtPyre_2F_EventScript_PokefanM : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(MtPyre_2F.TumbledFromFloorAbove)
}

internal val MtPyre_2FScripts: Map<String, Script> =
    mapOf(
        "MtPyre_2F_EventScript_Woman" to MtPyre_2F_EventScript_Woman,
        "MtPyre_2F_EventScript_PokefanM" to MtPyre_2F_EventScript_PokefanM,
    )
