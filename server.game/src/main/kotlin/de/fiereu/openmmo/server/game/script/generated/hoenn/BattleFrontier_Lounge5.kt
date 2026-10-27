package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.BattleFrontier_Lounge5
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object BattleFrontier_Lounge5_EventScript_Gentleman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_Lounge5.LadyClaimsSheUnderstandsPokemon)
}

internal object BattleFrontier_Lounge5_EventScript_BlackBelt : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_Lounge5.GirlSayingSomethingProfound)
}

internal object BattleFrontier_Lounge5_EventScript_LittleBoy : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_Lounge5.GirlPlaysAtRedHouseALot)
}

internal val BattleFrontier_Lounge5Scripts: Map<String, Script> =
    mapOf(
        "BattleFrontier_Lounge5_EventScript_Gentleman" to
            BattleFrontier_Lounge5_EventScript_Gentleman,
        "BattleFrontier_Lounge5_EventScript_BlackBelt" to
            BattleFrontier_Lounge5_EventScript_BlackBelt,
        "BattleFrontier_Lounge5_EventScript_LittleBoy" to
            BattleFrontier_Lounge5_EventScript_LittleBoy,
    )
