package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.MauvilleCity_GameCorner
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object MauvilleCity_GameCorner_EventScript_Woman2 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(MauvilleCity_GameCorner.CoinsAreNeededToPlay)
}

internal object MauvilleCity_GameCorner_EventScript_Gentleman : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(MauvilleCity_GameCorner.RouletteOnlyLuck)
}

internal val MauvilleCity_GameCornerScripts: Map<String, Script> =
    mapOf(
        "MauvilleCity_GameCorner_EventScript_Woman2" to MauvilleCity_GameCorner_EventScript_Woman2,
        "MauvilleCity_GameCorner_EventScript_Gentleman" to
            MauvilleCity_GameCorner_EventScript_Gentleman,
    )
