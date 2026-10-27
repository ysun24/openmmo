package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.LavaridgeTown_House
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object LavaridgeTown_House_EventScript_OldMan : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LavaridgeTown_House.WifeWarmingEggInHotSprings)
}

internal val LavaridgeTown_HouseScripts: Map<String, Script> =
    mapOf(
        "LavaridgeTown_House_EventScript_OldMan" to LavaridgeTown_House_EventScript_OldMan,
    )
