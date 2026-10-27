package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.BattleFrontier_Mart
import de.fiereu.openmmo.dialog.generated.hoenn.Misc
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object BattleFrontier_Mart_EventScript_Clerk : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.gText_HowMayIServeYou)
    ctx.pokemart(
        Items.ULTRA_BALL,
        Items.HYPER_POTION,
        Items.MAX_POTION,
        Items.FULL_RESTORE,
        Items.FULL_HEAL,
        Items.REVIVE,
        Items.MAX_REPEL,
        Items.PROTEIN,
        Items.CALCIUM,
        Items.IRON,
        Items.ZINC,
        Items.CARBOS,
        Items.HP_UP)
  }
}

internal object BattleFrontier_Mart_EventScript_OldMan : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(BattleFrontier_Mart.ChaperonGrandson)
}

internal object BattleFrontier_Mart_EventScript_Boy : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_Mart.FacilitiesDontAllowItems)
}

internal val BattleFrontier_MartScripts: Map<String, Script> =
    mapOf(
        "BattleFrontier_Mart_EventScript_Clerk" to BattleFrontier_Mart_EventScript_Clerk,
        "BattleFrontier_Mart_EventScript_OldMan" to BattleFrontier_Mart_EventScript_OldMan,
        "BattleFrontier_Mart_EventScript_Boy" to BattleFrontier_Mart_EventScript_Boy,
    )
