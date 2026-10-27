package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.BattleFrontier_Lounge2
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object BattleFrontier_Lounge2_EventScript_Maniac1 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(BattleFrontier_Lounge2.NewsGatheringPower)
}

internal object BattleFrontier_Lounge2_EventScript_Maniac2 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_Lounge2.AmazingPowersOfObservation)
}

internal object BattleFrontier_Lounge2_EventScript_TriathleteF : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_Lounge2.ThisPlaceIsScaringMe)
}

internal object BattleFrontier_Lounge2_EventScript_Maniac3 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_Lounge2.AmazingPowerOfPersuasion)
}

internal val BattleFrontier_Lounge2Scripts: Map<String, Script> =
    mapOf(
        "BattleFrontier_Lounge2_EventScript_Maniac1" to BattleFrontier_Lounge2_EventScript_Maniac1,
        "BattleFrontier_Lounge2_EventScript_Maniac2" to BattleFrontier_Lounge2_EventScript_Maniac2,
        "BattleFrontier_Lounge2_EventScript_TriathleteF" to
            BattleFrontier_Lounge2_EventScript_TriathleteF,
        "BattleFrontier_Lounge2_EventScript_Maniac3" to BattleFrontier_Lounge2_EventScript_Maniac3,
    )
