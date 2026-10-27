package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.SSAnne_B1F_Room5
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SSAnne_B1F_Room5_EventScript_Boy : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(SSAnne_B1F_Room5.MachokeHasStrengthToMoveRocks)
}

internal val SSAnne_B1F_Room5Scripts: Map<String, Script> =
    mapOf(
        "SSAnne_B1F_Room5_EventScript_Boy" to SSAnne_B1F_Room5_EventScript_Boy,
    )
