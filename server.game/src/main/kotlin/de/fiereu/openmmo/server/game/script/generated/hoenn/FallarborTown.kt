package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.FallarborTown
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object FallarborTown_EventScript_Girl : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(FallarborTown.MyPreciousAzurill)
}

internal object FallarborTown_EventScript_Gentleman : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(FallarborTown.HaveYouChallengedFlannery)
}

internal object FallarborTown_EventScript_BattleTentSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(FallarborTown.BattleTentSign)
}

internal object FallarborTown_EventScript_TownSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(FallarborTown.TownSign)
}

internal object FallarborTown_EventScript_MoveTutorSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(FallarborTown.MoveTutorSign)
}

internal val FallarborTownScripts: Map<String, Script> =
    mapOf(
        "FallarborTown_EventScript_Girl" to FallarborTown_EventScript_Girl,
        "FallarborTown_EventScript_Gentleman" to FallarborTown_EventScript_Gentleman,
        "FallarborTown_EventScript_BattleTentSign" to FallarborTown_EventScript_BattleTentSign,
        "FallarborTown_EventScript_TownSign" to FallarborTown_EventScript_TownSign,
        "FallarborTown_EventScript_MoveTutorSign" to FallarborTown_EventScript_MoveTutorSign,
    )
