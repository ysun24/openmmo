package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.BattleFrontier_OutsideWest
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object BattleFrontier_OutsideWest_EventScript_Boy1 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_OutsideWest.BestOutOfAllMyFriends)
}

internal object BattleFrontier_OutsideWest_EventScript_ExpertM : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_OutsideWest.WontLetGentlemenBeatMe)
}

internal object BattleFrontier_OutsideWest_EventScript_Man1 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_OutsideWest.OnlyToughTrainersBroughtHere)
}

internal object BattleFrontier_OutsideWest_EventScript_Woman1 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_OutsideWest.FinallyArrivedAtFrontier)
}

internal object BattleFrontier_OutsideWest_EventScript_FatMan1 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_OutsideWest.SquareFilledWithToughPeople)
}

internal object BattleFrontier_OutsideWest_EventScript_Gentleman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_OutsideWest.YoureOffToChallengeDome)
}

internal object BattleFrontier_OutsideWest_EventScript_Man2 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_OutsideWest.NothingHereNotLongAgo)
}

internal object BattleFrontier_OutsideWest_EventScript_Lass : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_OutsideWest.KeepBattlingUntilIGetSymbol)
}

internal object BattleFrontier_OutsideWest_EventScript_Man3 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_OutsideWest.LongDreamedAboutBattleFrontier)
}

internal object BattleFrontier_OutsideWest_EventScript_Fisherman1 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_OutsideWest.ChooseFishingOverBattling)
}

internal object BattleFrontier_OutsideWest_EventScript_FatMan2 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_OutsideWest.MetOlderGirlAtPike)
}

internal object BattleFrontier_OutsideWest_EventScript_Woman3 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_OutsideWest.LastTimeOurEyesMet)
}

internal object BattleFrontier_OutsideWest_EventScript_Boy2 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_OutsideWest.DomeAceLookedBecauseOfMyCheering)
}

internal object BattleFrontier_OutsideWest_EventScript_OldMan : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(BattleFrontier_OutsideWest.DomeAceIsMine)
}

internal object BattleFrontier_OutsideWest_EventScript_PokefanF : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_OutsideWest.MonWithLongTailInFrontier)
}

internal object BattleFrontier_OutsideWest_EventScript_BattleDomeSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(BattleFrontier_OutsideWest.BattleDomeSign)
}

internal object BattleFrontier_OutsideWest_EventScript_BattlePikeSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(BattleFrontier_OutsideWest.BattlePikeSign)
}

internal object BattleFrontier_OutsideWest_EventScript_BattleFactorySign : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(BattleFrontier_OutsideWest.BattleFactorySign)
}

internal val BattleFrontier_OutsideWestScripts: Map<String, Script> =
    mapOf(
        "BattleFrontier_OutsideWest_EventScript_Boy1" to
            BattleFrontier_OutsideWest_EventScript_Boy1,
        "BattleFrontier_OutsideWest_EventScript_ExpertM" to
            BattleFrontier_OutsideWest_EventScript_ExpertM,
        "BattleFrontier_OutsideWest_EventScript_Man1" to
            BattleFrontier_OutsideWest_EventScript_Man1,
        "BattleFrontier_OutsideWest_EventScript_Woman1" to
            BattleFrontier_OutsideWest_EventScript_Woman1,
        "BattleFrontier_OutsideWest_EventScript_FatMan1" to
            BattleFrontier_OutsideWest_EventScript_FatMan1,
        "BattleFrontier_OutsideWest_EventScript_Gentleman" to
            BattleFrontier_OutsideWest_EventScript_Gentleman,
        "BattleFrontier_OutsideWest_EventScript_Man2" to
            BattleFrontier_OutsideWest_EventScript_Man2,
        "BattleFrontier_OutsideWest_EventScript_Lass" to
            BattleFrontier_OutsideWest_EventScript_Lass,
        "BattleFrontier_OutsideWest_EventScript_Man3" to
            BattleFrontier_OutsideWest_EventScript_Man3,
        "BattleFrontier_OutsideWest_EventScript_Fisherman1" to
            BattleFrontier_OutsideWest_EventScript_Fisherman1,
        "BattleFrontier_OutsideWest_EventScript_FatMan2" to
            BattleFrontier_OutsideWest_EventScript_FatMan2,
        "BattleFrontier_OutsideWest_EventScript_Woman3" to
            BattleFrontier_OutsideWest_EventScript_Woman3,
        "BattleFrontier_OutsideWest_EventScript_Boy2" to
            BattleFrontier_OutsideWest_EventScript_Boy2,
        "BattleFrontier_OutsideWest_EventScript_OldMan" to
            BattleFrontier_OutsideWest_EventScript_OldMan,
        "BattleFrontier_OutsideWest_EventScript_PokefanF" to
            BattleFrontier_OutsideWest_EventScript_PokefanF,
        "BattleFrontier_OutsideWest_EventScript_BattleDomeSign" to
            BattleFrontier_OutsideWest_EventScript_BattleDomeSign,
        "BattleFrontier_OutsideWest_EventScript_BattlePikeSign" to
            BattleFrontier_OutsideWest_EventScript_BattlePikeSign,
        "BattleFrontier_OutsideWest_EventScript_BattleFactorySign" to
            BattleFrontier_OutsideWest_EventScript_BattleFactorySign,
    )
