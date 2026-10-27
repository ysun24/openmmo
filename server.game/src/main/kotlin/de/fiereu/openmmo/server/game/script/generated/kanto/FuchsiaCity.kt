package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.FuchsiaCity
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object FuchsiaCity_EventScript_Erik : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(FuchsiaCity.WheresSara)
}

internal object FuchsiaCity_EventScript_Youngster : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(FuchsiaCity.ItemBallInThere)
}

internal object FuchsiaCity_EventScript_LittleBoy : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(FuchsiaCity.DidYouTrySafariGame)
}

internal object FuchsiaCity_EventScript_OldMan : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(FuchsiaCity.SafariZoneZooInFront)
}

internal object FuchsiaCity_EventScript_CitySign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(FuchsiaCity.CitySign)
}

internal object FuchsiaCity_EventScript_SafariZoneSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(FuchsiaCity.SafariZoneSign)
}

internal object FuchsiaCity_EventScript_WardensHomeSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(FuchsiaCity.WardensHomeSign)
}

internal object FuchsiaCity_EventScript_SafariGameSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(FuchsiaCity.SafariGameSign)
}

internal val FuchsiaCityScripts: Map<String, Script> =
    mapOf(
        "FuchsiaCity_EventScript_Erik" to FuchsiaCity_EventScript_Erik,
        "FuchsiaCity_EventScript_Youngster" to FuchsiaCity_EventScript_Youngster,
        "FuchsiaCity_EventScript_LittleBoy" to FuchsiaCity_EventScript_LittleBoy,
        "FuchsiaCity_EventScript_OldMan" to FuchsiaCity_EventScript_OldMan,
        "FuchsiaCity_EventScript_CitySign" to FuchsiaCity_EventScript_CitySign,
        "FuchsiaCity_EventScript_SafariZoneSign" to FuchsiaCity_EventScript_SafariZoneSign,
        "FuchsiaCity_EventScript_WardensHomeSign" to FuchsiaCity_EventScript_WardensHomeSign,
        "FuchsiaCity_EventScript_SafariGameSign" to FuchsiaCity_EventScript_SafariGameSign,
    )
