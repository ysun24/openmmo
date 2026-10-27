package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.SSAnne_CaptainsOffice
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SSAnne_CaptainsOffice_EventScript_Book : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(SSAnne_CaptainsOffice.HowToConquerSeasickness)
}

internal object SSAnne_CaptainsOffice_EventScript_TrashCan : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(SSAnne_CaptainsOffice.YuckShouldntHaveLooked)
}

internal val SSAnne_CaptainsOfficeScripts: Map<String, Script> =
    mapOf(
        "SSAnne_CaptainsOffice_EventScript_Book" to SSAnne_CaptainsOffice_EventScript_Book,
        "SSAnne_CaptainsOffice_EventScript_TrashCan" to SSAnne_CaptainsOffice_EventScript_TrashCan,
    )
