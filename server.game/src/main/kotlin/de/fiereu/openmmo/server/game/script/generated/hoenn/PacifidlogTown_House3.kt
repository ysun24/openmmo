package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.PacifidlogTown_House3
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object PacifidlogTown_House3_EventScript_Girl : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(PacifidlogTown_House3.IsThatAPokedex)
}

internal val PacifidlogTown_House3Scripts: Map<String, Script> =
    mapOf(
        "PacifidlogTown_House3_EventScript_Girl" to PacifidlogTown_House3_EventScript_Girl,
    )
