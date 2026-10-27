package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.SSAnne_1F_Room3
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SSAnne_1F_Room3_EventScript_LittleGirl : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(SSAnne_1F_Room3.AlwaysTravelWithWigglytuff)
}

internal object SSAnne_1F_Room3_EventScript_BaldingMan : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(SSAnne_1F_Room3.CruiseIsElegantAndCozy)
}

internal val SSAnne_1F_Room3Scripts: Map<String, Script> =
    mapOf(
        "SSAnne_1F_Room3_EventScript_LittleGirl" to SSAnne_1F_Room3_EventScript_LittleGirl,
        "SSAnne_1F_Room3_EventScript_BaldingMan" to SSAnne_1F_Room3_EventScript_BaldingMan,
    )
