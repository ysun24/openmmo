package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Route102
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route102_EventScript_LittleBoy : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route102.ImNotVeryTall)
}

internal object Route102_EventScript_Boy : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route102.CatchWholeBunchOfPokemon)
}

internal object Route102_EventScript_RouteSignPetalburg : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route102.RouteSignPetalburg)
}

internal object Route102_EventScript_RouteSignOldale : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route102.RouteSignOldale)
}

internal val Route102Scripts: Map<String, Script> =
    mapOf(
        "Route102_EventScript_LittleBoy" to Route102_EventScript_LittleBoy,
        "Route102_EventScript_Boy" to Route102_EventScript_Boy,
        "Route102_EventScript_RouteSignPetalburg" to Route102_EventScript_RouteSignPetalburg,
        "Route102_EventScript_RouteSignOldale" to Route102_EventScript_RouteSignOldale,
    )
