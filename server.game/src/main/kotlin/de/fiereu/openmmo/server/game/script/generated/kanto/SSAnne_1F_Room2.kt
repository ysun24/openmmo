package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.SSAnne_1F_Room2
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SSAnne_1F_Room2_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(SSAnne_1F_Room2.CruisingAroundWorld)
}

internal val SSAnne_1F_Room2Scripts: Map<String, Script> =
    mapOf(
        "SSAnne_1F_Room2_EventScript_Woman" to SSAnne_1F_Room2_EventScript_Woman,
    )
