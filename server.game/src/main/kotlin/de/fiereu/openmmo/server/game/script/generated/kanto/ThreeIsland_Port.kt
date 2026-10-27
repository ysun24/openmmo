package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.ThreeIsland_Port
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object ThreeIsland_Port_EventScript_Biker1 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(ThreeIsland_Port.WereKantoRiderFederation)
}

internal object ThreeIsland_Port_EventScript_Biker2 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(ThreeIsland_Port.ForkOverMoney)
}

internal val ThreeIsland_PortScripts: Map<String, Script> =
    mapOf(
        "ThreeIsland_Port_EventScript_Biker1" to ThreeIsland_Port_EventScript_Biker1,
        "ThreeIsland_Port_EventScript_Biker2" to ThreeIsland_Port_EventScript_Biker2,
    )
