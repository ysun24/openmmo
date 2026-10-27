package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.VermilionCity_House3
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object VermilionCity_House3_EventScript_Boy : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(VermilionCity_House3.PidgeyFlyLetterToSaffron)
}

internal object VermilionCity_House3_EventScript_Lass : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(VermilionCity_House3.SendMyPidgeyToUnionRoom)
}

internal object VermilionCity_House3_EventScript_Letter : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(VermilionCity_House3.DearPippiLetter)
}

internal val VermilionCity_House3Scripts: Map<String, Script> =
    mapOf(
        "VermilionCity_House3_EventScript_Boy" to VermilionCity_House3_EventScript_Boy,
        "VermilionCity_House3_EventScript_Lass" to VermilionCity_House3_EventScript_Lass,
        "VermilionCity_House3_EventScript_Letter" to VermilionCity_House3_EventScript_Letter,
    )
