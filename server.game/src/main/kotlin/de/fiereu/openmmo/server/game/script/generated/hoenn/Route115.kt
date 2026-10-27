package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Route115
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route115_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(Route115.NeverKnowWhenCavePokemonWillAppear)
}

internal object Route115_EventScript_RouteSignRustboro : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route115.RouteSignRustboro)
}

internal object Route115_EventScript_MeteorFallsSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route115.MeteorFallsSign)
}

internal val Route115Scripts: Map<String, Script> =
    mapOf(
        "Route115_EventScript_Woman" to Route115_EventScript_Woman,
        "Route115_EventScript_RouteSignRustboro" to Route115_EventScript_RouteSignRustboro,
        "Route115_EventScript_MeteorFallsSign" to Route115_EventScript_MeteorFallsSign,
    )
