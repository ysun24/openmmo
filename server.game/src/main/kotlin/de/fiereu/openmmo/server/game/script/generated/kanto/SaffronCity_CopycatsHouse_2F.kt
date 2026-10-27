package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.SaffronCity_CopycatsHouse_2F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SaffronCity_CopycatsHouse_2F_EventScript_Doll : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(SaffronCity_CopycatsHouse_2F.RareMonOnlyDoll)
}

internal object SaffronCity_CopycatsHouse_2F_EventScript_Computer : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SaffronCity_CopycatsHouse_2F.MySecrets)
}

internal object SaffronCity_CopycatsHouse_2F_EventScript_Game : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(SaffronCity_CopycatsHouse_2F.MarioWearingABucket)
}

internal val SaffronCity_CopycatsHouse_2FScripts: Map<String, Script> =
    mapOf(
        "SaffronCity_CopycatsHouse_2F_EventScript_Doll" to
            SaffronCity_CopycatsHouse_2F_EventScript_Doll,
        "SaffronCity_CopycatsHouse_2F_EventScript_Computer" to
            SaffronCity_CopycatsHouse_2F_EventScript_Computer,
        "SaffronCity_CopycatsHouse_2F_EventScript_Game" to
            SaffronCity_CopycatsHouse_2F_EventScript_Game,
    )
