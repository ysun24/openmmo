package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.TwoIsland_JoyfulGameCorner
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object TwoIsland_JoyfulGameCorner_EventScript_Lostelle : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(TwoIsland_JoyfulGameCorner.PleaseShowMeYouBeingCool)
}

internal val TwoIsland_JoyfulGameCornerScripts: Map<String, Script> =
    mapOf(
        "TwoIsland_JoyfulGameCorner_EventScript_Lostelle" to
            TwoIsland_JoyfulGameCorner_EventScript_Lostelle,
    )
