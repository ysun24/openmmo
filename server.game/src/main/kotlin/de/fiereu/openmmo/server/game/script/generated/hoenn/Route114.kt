package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Route114
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route114_EventScript_MeteorFallsSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route114.MeteorFallsSign)
}

internal object Route114_EventScript_FossilManiacsHouseSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route114.FossilManiacsHouseSign)
}

internal object Route114_EventScript_LanettesHouseSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route114.LanettesHouse)
}

internal val Route114Scripts: Map<String, Script> =
    mapOf(
        "Route114_EventScript_MeteorFallsSign" to Route114_EventScript_MeteorFallsSign,
        "Route114_EventScript_FossilManiacsHouseSign" to
            Route114_EventScript_FossilManiacsHouseSign,
        "Route114_EventScript_LanettesHouseSign" to Route114_EventScript_LanettesHouseSign,
    )
