package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.FuchsiaCity_WardensHouse
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object FuchsiaCity_WardensHouse_EventScript_DisplaySign2 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(FuchsiaCity_WardensHouse.OldMonMerchandiseOnDisplay)
}

internal object FuchsiaCity_WardensHouse_EventScript_DisplaySign1 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(FuchsiaCity_WardensHouse.MonPhotosFossilsOnDisplay)
}

internal val FuchsiaCity_WardensHouseScripts: Map<String, Script> =
    mapOf(
        "FuchsiaCity_WardensHouse_EventScript_DisplaySign2" to
            FuchsiaCity_WardensHouse_EventScript_DisplaySign2,
        "FuchsiaCity_WardensHouse_EventScript_DisplaySign1" to
            FuchsiaCity_WardensHouse_EventScript_DisplaySign1,
    )
