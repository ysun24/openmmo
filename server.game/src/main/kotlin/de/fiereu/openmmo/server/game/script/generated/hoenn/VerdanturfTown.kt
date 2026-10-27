package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.VerdanturfTown
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object VerdanturfTown_EventScript_Man : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(VerdanturfTown.AirCleanHere)
}

internal object VerdanturfTown_EventScript_Camper : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(VerdanturfTown.MakeBattleTentDebut)
}

internal object VerdanturfTown_EventScript_TownSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(VerdanturfTown.TownSign)
}

internal object VerdanturfTown_EventScript_WandasHouseSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(VerdanturfTown.WandasHouse)
}

internal object VerdanturfTown_EventScript_BattleTentSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(VerdanturfTown.BattleTentSign)
}

internal object VerdanturfTown_EventScript_RusturfTunnelSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(VerdanturfTown.RusturfTunnelSign)
}

internal val VerdanturfTownScripts: Map<String, Script> =
    mapOf(
        "VerdanturfTown_EventScript_Man" to VerdanturfTown_EventScript_Man,
        "VerdanturfTown_EventScript_Camper" to VerdanturfTown_EventScript_Camper,
        "VerdanturfTown_EventScript_TownSign" to VerdanturfTown_EventScript_TownSign,
        "VerdanturfTown_EventScript_WandasHouseSign" to VerdanturfTown_EventScript_WandasHouseSign,
        "VerdanturfTown_EventScript_BattleTentSign" to VerdanturfTown_EventScript_BattleTentSign,
        "VerdanturfTown_EventScript_RusturfTunnelSign" to
            VerdanturfTown_EventScript_RusturfTunnelSign,
    )
