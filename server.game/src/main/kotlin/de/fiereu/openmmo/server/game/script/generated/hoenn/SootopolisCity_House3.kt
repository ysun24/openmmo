package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.SootopolisCity_House3
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SootopolisCity_House3_EventScript_Girl : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(SootopolisCity_House3.TrainerFanClubWasWild)
}

internal val SootopolisCity_House3Scripts: Map<String, Script> =
    mapOf(
        "SootopolisCity_House3_EventScript_Girl" to SootopolisCity_House3_EventScript_Girl,
    )
