package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.LavenderTown_Mart
import de.fiereu.openmmo.dialog.generated.kanto.Misc
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object LavenderTown_Mart_EventScript_Clerk : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.Text_MayIHelpYou)
    ctx.pokemart(
        Items.GREAT_BALL,
        Items.SUPER_POTION,
        Items.REVIVE,
        Items.ANTIDOTE,
        Items.PARLYZ_HEAL,
        Items.BURN_HEAL,
        Items.ICE_HEAL,
        Items.ESCAPE_ROPE,
        Items.SUPER_REPEL)
  }
}

internal object LavenderTown_Mart_EventScript_BaldingMan : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LavenderTown_Mart.SearchingForStatRaiseItems)
}

internal object LavenderTown_Mart_EventScript_Rocker : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(LavenderTown_Mart.DidYouBuyRevives)
}

internal object LavenderTown_Mart_EventScript_Youngster : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LavenderTown_Mart.TrainerDuosCanChallengeYou)
}

internal val LavenderTown_MartScripts: Map<String, Script> =
    mapOf(
        "LavenderTown_Mart_EventScript_Clerk" to LavenderTown_Mart_EventScript_Clerk,
        "LavenderTown_Mart_EventScript_BaldingMan" to LavenderTown_Mart_EventScript_BaldingMan,
        "LavenderTown_Mart_EventScript_Rocker" to LavenderTown_Mart_EventScript_Rocker,
        "LavenderTown_Mart_EventScript_Youngster" to LavenderTown_Mart_EventScript_Youngster,
    )
