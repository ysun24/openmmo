package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.MauvilleCity_Mart
import de.fiereu.openmmo.dialog.generated.hoenn.Misc
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object MauvilleCity_Mart_EventScript_Clerk : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.gText_HowMayIServeYou)
    ctx.pokemart(
        Items.POKE_BALL,
        Items.GREAT_BALL,
        Items.SUPER_POTION,
        Items.ANTIDOTE,
        Items.PARLYZ_HEAL,
        Items.AWAKENING,
        Items.X_SPEED,
        Items.X_ATTACK,
        Items.X_DEFEND,
        Items.GUARD_SPEC,
        Items.DIRE_HIT,
        Items.X_ACCURACY)
  }
}

internal object MauvilleCity_Mart_EventScript_ExpertM : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(MauvilleCity_Mart.ItemsToTemporarilyElevateStats)
}

internal object MauvilleCity_Mart_EventScript_Man : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(MauvilleCity_Mart.DecisionsDetermineBattle)
}

internal val MauvilleCity_MartScripts: Map<String, Script> =
    mapOf(
        "MauvilleCity_Mart_EventScript_Clerk" to MauvilleCity_Mart_EventScript_Clerk,
        "MauvilleCity_Mart_EventScript_ExpertM" to MauvilleCity_Mart_EventScript_ExpertM,
        "MauvilleCity_Mart_EventScript_Man" to MauvilleCity_Mart_EventScript_Man,
    )
