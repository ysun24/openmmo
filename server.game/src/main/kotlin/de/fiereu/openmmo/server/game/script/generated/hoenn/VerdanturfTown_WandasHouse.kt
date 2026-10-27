package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.VerdanturfTown_WandasHouse
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object VerdanturfTown_WandasHouse_EventScript_WandasBoyfriend : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(VerdanturfTown_WandasHouse.CanSeeGirlfriendEveryDay)
}

internal val VerdanturfTown_WandasHouseScripts: Map<String, Script> =
    mapOf(
        "VerdanturfTown_WandasHouse_EventScript_WandasBoyfriend" to
            VerdanturfTown_WandasHouse_EventScript_WandasBoyfriend,
    )
