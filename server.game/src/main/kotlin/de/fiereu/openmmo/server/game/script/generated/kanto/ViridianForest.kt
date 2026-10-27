package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.ViridianForest
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object ViridianForest_EventScript_Youngster : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(ViridianForest.FriendsItchingToBattle)
}

internal object ViridianForest_EventScript_Boy : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(ViridianForest.RanOutOfPokeBalls)
}

internal object ViridianForest_EventScript_TrainerTips1 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(ViridianForest.AvoidGrassyAreasWhenWeak)
}

internal object ViridianForest_EventScript_TrainerTips3 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(ViridianForest.ContactOakViaPCToRatePokedex)
}

internal object ViridianForest_EventScript_TrainerTips4 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(ViridianForest.CantCatchOwnedMons)
}

internal object ViridianForest_EventScript_ExitSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(ViridianForest.LeavingViridianForest)
}

internal object ViridianForest_EventScript_TrainerTips2 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(ViridianForest.UseAntidoteForPoison)
}

internal object ViridianForest_EventScript_TrainerTips5 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(ViridianForest.WeakenMonsBeforeCapture)
}

internal val ViridianForestScripts: Map<String, Script> =
    mapOf(
        "ViridianForest_EventScript_Youngster" to ViridianForest_EventScript_Youngster,
        "ViridianForest_EventScript_Boy" to ViridianForest_EventScript_Boy,
        "ViridianForest_EventScript_TrainerTips1" to ViridianForest_EventScript_TrainerTips1,
        "ViridianForest_EventScript_TrainerTips3" to ViridianForest_EventScript_TrainerTips3,
        "ViridianForest_EventScript_TrainerTips4" to ViridianForest_EventScript_TrainerTips4,
        "ViridianForest_EventScript_ExitSign" to ViridianForest_EventScript_ExitSign,
        "ViridianForest_EventScript_TrainerTips2" to ViridianForest_EventScript_TrainerTips2,
        "ViridianForest_EventScript_TrainerTips5" to ViridianForest_EventScript_TrainerTips5,
    )
