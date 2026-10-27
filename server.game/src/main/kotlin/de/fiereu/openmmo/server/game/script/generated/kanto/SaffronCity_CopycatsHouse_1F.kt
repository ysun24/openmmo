package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.SaffronCity_CopycatsHouse_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SaffronCity_CopycatsHouse_1F_EventScript_CopycatsDad : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(SaffronCity_CopycatsHouse_1F.DaughterLikesToMimicPeople)
}

internal object SaffronCity_CopycatsHouse_1F_EventScript_CopycatsMom : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(SaffronCity_CopycatsHouse_1F.DaughterIsSelfCentered)
}

internal val SaffronCity_CopycatsHouse_1FScripts: Map<String, Script> =
    mapOf(
        "SaffronCity_CopycatsHouse_1F_EventScript_CopycatsDad" to
            SaffronCity_CopycatsHouse_1F_EventScript_CopycatsDad,
        "SaffronCity_CopycatsHouse_1F_EventScript_CopycatsMom" to
            SaffronCity_CopycatsHouse_1F_EventScript_CopycatsMom,
    )
