package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Route2_House
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route2_House_EventScript_Scientist : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route2_House.FaintedMonsCanUseFieldMoves)
}

internal val Route2_HouseScripts: Map<String, Script> =
    mapOf(
        "Route2_House_EventScript_Scientist" to Route2_House_EventScript_Scientist,
    )
