package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.MossdeepCity_House1
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object MossdeepCity_House1_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(MossdeepCity_House1.HusbandCanTellPokeblockMonLikes)
}

internal val MossdeepCity_House1Scripts: Map<String, Script> =
    mapOf(
        "MossdeepCity_House1_EventScript_Woman" to MossdeepCity_House1_EventScript_Woman,
    )
