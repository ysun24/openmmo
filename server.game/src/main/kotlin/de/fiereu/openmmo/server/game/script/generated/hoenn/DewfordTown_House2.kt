package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.DewfordTown_House2
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object DewfordTown_House2_EventScript_Boy : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(DewfordTown_House2.BrawlySoCool)
}

internal val DewfordTown_House2Scripts: Map<String, Script> =
    mapOf(
        "DewfordTown_House2_EventScript_Boy" to DewfordTown_House2_EventScript_Boy,
    )
