package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Route114_LanettesHouse
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route114_LanettesHouse_EventScript_PC : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route114_LanettesHouse.EmailFromBill)
}

internal val Route114_LanettesHouseScripts: Map<String, Script> =
    mapOf(
        "Route114_LanettesHouse_EventScript_PC" to Route114_LanettesHouse_EventScript_PC,
    )
