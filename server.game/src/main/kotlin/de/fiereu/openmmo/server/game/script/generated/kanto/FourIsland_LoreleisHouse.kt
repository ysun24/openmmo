package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.FourIsland_LoreleisHouse
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object FourIsland_LoreleisHouse_EventScript_Doll : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(FourIsland_LoreleisHouse.StuffedMonDollsGalore)
}

internal val FourIsland_LoreleisHouseScripts: Map<String, Script> =
    mapOf(
        "FourIsland_LoreleisHouse_EventScript_Doll" to FourIsland_LoreleisHouse_EventScript_Doll,
    )
