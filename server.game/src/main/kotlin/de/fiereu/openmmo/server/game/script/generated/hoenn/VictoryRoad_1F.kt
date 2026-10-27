package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.VictoryRoad_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object VictoryRoad_1F_EventScript_EntranceWally : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(VictoryRoad_1F.WallyPostEntranceBattle)
}

internal val VictoryRoad_1FScripts: Map<String, Script> =
    mapOf(
        "VictoryRoad_1F_EventScript_EntranceWally" to VictoryRoad_1F_EventScript_EntranceWally,
    )
