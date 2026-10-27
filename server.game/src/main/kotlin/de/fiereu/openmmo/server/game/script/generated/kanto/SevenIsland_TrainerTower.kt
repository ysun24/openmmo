package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.SevenIsland_TrainerTower
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SevenIsland_TrainerTower_EventScript_TrainerTowerSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SevenIsland_TrainerTower.TrainerTowerSign)
}

internal object SevenIsland_TrainerTower_EventScript_TrainerTowerAheadSign : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(SevenIsland_TrainerTower.TrainerTowerAhead)
}

internal val SevenIsland_TrainerTowerScripts: Map<String, Script> =
    mapOf(
        "SevenIsland_TrainerTower_EventScript_TrainerTowerSign" to
            SevenIsland_TrainerTower_EventScript_TrainerTowerSign,
        "SevenIsland_TrainerTower_EventScript_TrainerTowerAheadSign" to
            SevenIsland_TrainerTower_EventScript_TrainerTowerAheadSign,
    )
