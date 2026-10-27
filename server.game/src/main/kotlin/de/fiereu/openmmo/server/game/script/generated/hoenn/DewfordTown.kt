package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.DewfordTown
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object DewfordTown_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(DewfordTown.TinyIslandCommunity)
}

internal object DewfordTown_EventScript_TownSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(DewfordTown.TownSign)
}

internal object DewfordTown_EventScript_GymSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(DewfordTown.GymSign)
}

internal object DewfordTown_EventScript_HallSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(DewfordTown.HallSign)
}

internal val DewfordTownScripts: Map<String, Script> =
    mapOf(
        "DewfordTown_EventScript_Woman" to DewfordTown_EventScript_Woman,
        "DewfordTown_EventScript_TownSign" to DewfordTown_EventScript_TownSign,
        "DewfordTown_EventScript_GymSign" to DewfordTown_EventScript_GymSign,
        "DewfordTown_EventScript_HallSign" to DewfordTown_EventScript_HallSign,
    )
