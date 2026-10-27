package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Route16_NorthEntrance_2F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route16_NorthEntrance_2F_EventScript_LittleBoy : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(Route16_NorthEntrance_2F.OnBikeRideWithGirlfriend)
}

internal object Route16_NorthEntrance_2F_EventScript_LittleGirl : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(Route16_NorthEntrance_2F.RidingTogetherOnNewBikes)
}

internal object Route16_NorthEntrance_2F_EventScript_LeftBinoculars : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(Route16_NorthEntrance_2F.ItsCeladonDeptStore)
}

internal object Route16_NorthEntrance_2F_EventScript_RightBinoculars : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(Route16_NorthEntrance_2F.LongPathOverWater)
}

internal val Route16_NorthEntrance_2FScripts: Map<String, Script> =
    mapOf(
        "Route16_NorthEntrance_2F_EventScript_LittleBoy" to
            Route16_NorthEntrance_2F_EventScript_LittleBoy,
        "Route16_NorthEntrance_2F_EventScript_LittleGirl" to
            Route16_NorthEntrance_2F_EventScript_LittleGirl,
        "Route16_NorthEntrance_2F_EventScript_LeftBinoculars" to
            Route16_NorthEntrance_2F_EventScript_LeftBinoculars,
        "Route16_NorthEntrance_2F_EventScript_RightBinoculars" to
            Route16_NorthEntrance_2F_EventScript_RightBinoculars,
    )
