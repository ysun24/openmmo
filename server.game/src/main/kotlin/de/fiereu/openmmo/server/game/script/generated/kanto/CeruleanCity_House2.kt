package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.CeruleanCity_House2
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object CeruleanCity_House2_EventScript_Lass : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(CeruleanCity_House2.TeamRocketTryingToDigIntoNoGood)
}

internal object CeruleanCity_House2_EventScript_WallHole : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(CeruleanCity_House2.TeamRocketLeftWayOut)
}

internal val CeruleanCity_House2Scripts: Map<String, Script> =
    mapOf(
        "CeruleanCity_House2_EventScript_Lass" to CeruleanCity_House2_EventScript_Lass,
        "CeruleanCity_House2_EventScript_WallHole" to CeruleanCity_House2_EventScript_WallHole,
    )
