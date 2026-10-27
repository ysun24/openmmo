package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.SafariZone_East
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SafariZone_East_EventScript_AreaSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SafariZone_East.AreaSign)
}

internal object SafariZone_East_EventScript_RestHouseSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SafariZone_East.RestHouse)
}

internal object SafariZone_East_EventScript_TrainerTips : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(SafariZone_East.TimeDeclinesOnlyWhileYouWalk)
}

internal val SafariZone_EastScripts: Map<String, Script> =
    mapOf(
        "SafariZone_East_EventScript_AreaSign" to SafariZone_East_EventScript_AreaSign,
        "SafariZone_East_EventScript_RestHouseSign" to SafariZone_East_EventScript_RestHouseSign,
        "SafariZone_East_EventScript_TrainerTips" to SafariZone_East_EventScript_TrainerTips,
    )
