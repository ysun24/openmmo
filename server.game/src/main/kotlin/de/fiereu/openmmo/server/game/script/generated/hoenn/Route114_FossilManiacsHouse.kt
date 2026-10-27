package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Route114_FossilManiacsHouse
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route114_FossilManiacsHouse_EventScript_RockDisplay : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(Route114_FossilManiacsHouse.RocksFillDisplayCase)
}

internal object Route114_FossilManiacsHouse_EventScript_Bookshelf : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(Route114_FossilManiacsHouse.CrammedWithBooks)
}

internal val Route114_FossilManiacsHouseScripts: Map<String, Script> =
    mapOf(
        "Route114_FossilManiacsHouse_EventScript_RockDisplay" to
            Route114_FossilManiacsHouse_EventScript_RockDisplay,
        "Route114_FossilManiacsHouse_EventScript_Bookshelf" to
            Route114_FossilManiacsHouse_EventScript_Bookshelf,
    )
