package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.ViridianCity_House
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object ViridianCity_House_EventScript_BaldingMan : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(ViridianCity_House.NicknamingIsFun)
}

internal object ViridianCity_House_EventScript_LittleGirl : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(ViridianCity_House.MyDaddyLovesMonsToo)
}

internal object ViridianCity_House_EventScript_NicknameSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(ViridianCity_House.SpearowNameSpeary)
}

internal val ViridianCity_HouseScripts: Map<String, Script> =
    mapOf(
        "ViridianCity_House_EventScript_BaldingMan" to ViridianCity_House_EventScript_BaldingMan,
        "ViridianCity_House_EventScript_LittleGirl" to ViridianCity_House_EventScript_LittleGirl,
        "ViridianCity_House_EventScript_NicknameSign" to
            ViridianCity_House_EventScript_NicknameSign,
    )
