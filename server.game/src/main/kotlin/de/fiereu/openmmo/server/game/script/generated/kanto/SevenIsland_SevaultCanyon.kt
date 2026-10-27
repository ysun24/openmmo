package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.SevenIsland_SevaultCanyon
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SevenIsland_SevaultCanyon_EventScript_RouteSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SevenIsland_SevaultCanyon.RouteSign)
}

internal val SevenIsland_SevaultCanyonScripts: Map<String, Script> =
    mapOf(
        "SevenIsland_SevaultCanyon_EventScript_RouteSign" to
            SevenIsland_SevaultCanyon_EventScript_RouteSign,
    )
