package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.PewterCity_Museum_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object PewterCity_Museum_1F_EventScript_OldMan : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(PewterCity_Museum_1F.ShouldBeGratefulForLongLife)
}

internal object PewterCity_Museum_1F_EventScript_OldAmber : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(PewterCity_Museum_1F.BeautifulPieceOfAmber)
}

internal object PewterCity_Museum_1F_EventScript_Scientist2 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(PewterCity_Museum_1F.WeHaveTwoFossilsOnExhibit)
}

internal val PewterCity_Museum_1FScripts: Map<String, Script> =
    mapOf(
        "PewterCity_Museum_1F_EventScript_OldMan" to PewterCity_Museum_1F_EventScript_OldMan,
        "PewterCity_Museum_1F_EventScript_OldAmber" to PewterCity_Museum_1F_EventScript_OldAmber,
        "PewterCity_Museum_1F_EventScript_Scientist2" to
            PewterCity_Museum_1F_EventScript_Scientist2,
    )
