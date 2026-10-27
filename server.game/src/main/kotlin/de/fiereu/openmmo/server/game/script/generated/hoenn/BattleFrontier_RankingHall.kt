package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.BattleFrontier_RankingHall
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object BattleFrontier_RankingHall_EventScript_Attendant : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_RankingHall.ExplainRankingHall)
}

internal object BattleFrontier_RankingHall_EventScript_Boy : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_RankingHall.MyNamesNotUpThere)
}

internal object BattleFrontier_RankingHall_EventScript_DomePikeFactoryRecordsSign : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(BattleFrontier_RankingHall.DomePikeFactoryRecords)
}

internal object BattleFrontier_RankingHall_EventScript_PalaceArenaPyramidRecordsSIgn : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(BattleFrontier_RankingHall.PalaceArenaPyramidRecords)
}

internal val BattleFrontier_RankingHallScripts: Map<String, Script> =
    mapOf(
        "BattleFrontier_RankingHall_EventScript_Attendant" to
            BattleFrontier_RankingHall_EventScript_Attendant,
        "BattleFrontier_RankingHall_EventScript_Boy" to BattleFrontier_RankingHall_EventScript_Boy,
        "BattleFrontier_RankingHall_EventScript_DomePikeFactoryRecordsSign" to
            BattleFrontier_RankingHall_EventScript_DomePikeFactoryRecordsSign,
        "BattleFrontier_RankingHall_EventScript_PalaceArenaPyramidRecordsSIgn" to
            BattleFrontier_RankingHall_EventScript_PalaceArenaPyramidRecordsSIgn,
    )
