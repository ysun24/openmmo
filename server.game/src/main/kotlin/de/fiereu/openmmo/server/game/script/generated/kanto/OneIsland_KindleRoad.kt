package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.OneIsland_KindleRoad
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object OneIsland_KindleRoad_EventScript_EmberSpaSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(OneIsland_KindleRoad.EmberSpaSign)
}

internal object OneIsland_KindleRoad_EventScript_RouteSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(OneIsland_KindleRoad.RouteSign)
}

internal val OneIsland_KindleRoadScripts: Map<String, Script> =
    mapOf(
        "OneIsland_KindleRoad_EventScript_EmberSpaSign" to
            OneIsland_KindleRoad_EventScript_EmberSpaSign,
        "OneIsland_KindleRoad_EventScript_RouteSign" to OneIsland_KindleRoad_EventScript_RouteSign,
    )
