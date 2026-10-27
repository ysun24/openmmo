package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.SootopolisCity_House4
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SootopolisCity_House4_EventScript_Man : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(SootopolisCity_House4.AncientTreasuresWaitingInSea)
}

internal object SootopolisCity_House4_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(SootopolisCity_House4.StrollUnderwaterWithPokemon)
}

internal val SootopolisCity_House4Scripts: Map<String, Script> =
    mapOf(
        "SootopolisCity_House4_EventScript_Man" to SootopolisCity_House4_EventScript_Man,
        "SootopolisCity_House4_EventScript_Woman" to SootopolisCity_House4_EventScript_Woman,
    )
