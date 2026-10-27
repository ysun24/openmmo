package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.MossdeepCity_SpaceCenter_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object MossdeepCity_SpaceCenter_1F_EventScript_MagmaNote : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(MossdeepCity_SpaceCenter_1F.MagmaIntentToStealNotice)
}

internal val MossdeepCity_SpaceCenter_1FScripts: Map<String, Script> =
    mapOf(
        "MossdeepCity_SpaceCenter_1F_EventScript_MagmaNote" to
            MossdeepCity_SpaceCenter_1F_EventScript_MagmaNote,
    )
