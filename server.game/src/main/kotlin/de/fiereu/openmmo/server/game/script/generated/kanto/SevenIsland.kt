package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.SevenIsland
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SevenIsland_EventScript_OldWoman : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(SevenIsland.IslandsMadeInSevenDays)
}

internal object SevenIsland_EventScript_Scientist : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(SevenIsland.IslandVirtuallyUntouched)
}

internal object SevenIsland_EventScript_IslandSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SevenIsland.IslandSign)
}

internal val SevenIslandScripts: Map<String, Script> =
    mapOf(
        "SevenIsland_EventScript_OldWoman" to SevenIsland_EventScript_OldWoman,
        "SevenIsland_EventScript_Scientist" to SevenIsland_EventScript_Scientist,
        "SevenIsland_EventScript_IslandSign" to SevenIsland_EventScript_IslandSign,
    )
