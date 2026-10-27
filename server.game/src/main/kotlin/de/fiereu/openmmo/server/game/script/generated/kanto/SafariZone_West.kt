package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.SafariZone_West
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SafariZone_West_EventScript_LostTeethNotice : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(SafariZone_West.PleaseFindWardensLostTeeth)
}

internal object SafariZone_West_EventScript_TrainerTips : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SafariZone_West.SearchForSecretHouse)
}

internal object SafariZone_West_EventScript_AreaSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SafariZone_West.AreaSign)
}

internal object SafariZone_West_EventScript_RestHouseSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SafariZone_West.RestHouse)
}

internal val SafariZone_WestScripts: Map<String, Script> =
    mapOf(
        "SafariZone_West_EventScript_LostTeethNotice" to
            SafariZone_West_EventScript_LostTeethNotice,
        "SafariZone_West_EventScript_TrainerTips" to SafariZone_West_EventScript_TrainerTips,
        "SafariZone_West_EventScript_AreaSign" to SafariZone_West_EventScript_AreaSign,
        "SafariZone_West_EventScript_RestHouseSign" to SafariZone_West_EventScript_RestHouseSign,
    )
