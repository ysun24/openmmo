package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.PalletTown_PlayersHouse_2F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object PalletTown_PlayersHouse_2F_EventScript_NES : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(PalletTown_PlayersHouse_2F.PlayedWithNES)
}

internal object PalletTown_PlayersHouse_2F_EventScript_Sign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(PalletTown_PlayersHouse_2F.PressLRForHelp)
}

internal val PalletTown_PlayersHouse_2FScripts: Map<String, Script> =
    mapOf(
        "PalletTown_PlayersHouse_2F_EventScript_NES" to PalletTown_PlayersHouse_2F_EventScript_NES,
        "PalletTown_PlayersHouse_2F_EventScript_Sign" to
            PalletTown_PlayersHouse_2F_EventScript_Sign,
    )
