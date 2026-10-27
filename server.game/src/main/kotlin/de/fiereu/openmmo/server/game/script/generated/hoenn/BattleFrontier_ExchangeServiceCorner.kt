package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.BattleFrontier_ExchangeServiceCorner
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object BattleFrontier_ExchangeServiceCorner_EventScript_RichBoy : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_ExchangeServiceCorner.WishIHadAllDolls)
}

internal object BattleFrontier_ExchangeServiceCorner_EventScript_PokefanF : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_ExchangeServiceCorner.GetYouAnythingYouWant)
}

internal object BattleFrontier_ExchangeServiceCorner_EventScript_Sailor : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_ExchangeServiceCorner.ItemsWillGetMonTougher)
}

internal object BattleFrontier_ExchangeServiceCorner_EventScript_Man : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_ExchangeServiceCorner.GoGetYourOwnDoll)
}

internal object BattleFrontier_ExchangeServiceCorner_EventScript_Girl : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_ExchangeServiceCorner.MoreBattlePointsForRecord)
}

internal val BattleFrontier_ExchangeServiceCornerScripts: Map<String, Script> =
    mapOf(
        "BattleFrontier_ExchangeServiceCorner_EventScript_RichBoy" to
            BattleFrontier_ExchangeServiceCorner_EventScript_RichBoy,
        "BattleFrontier_ExchangeServiceCorner_EventScript_PokefanF" to
            BattleFrontier_ExchangeServiceCorner_EventScript_PokefanF,
        "BattleFrontier_ExchangeServiceCorner_EventScript_Sailor" to
            BattleFrontier_ExchangeServiceCorner_EventScript_Sailor,
        "BattleFrontier_ExchangeServiceCorner_EventScript_Man" to
            BattleFrontier_ExchangeServiceCorner_EventScript_Man,
        "BattleFrontier_ExchangeServiceCorner_EventScript_Girl" to
            BattleFrontier_ExchangeServiceCorner_EventScript_Girl,
    )
