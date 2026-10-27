package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.LavaridgeTown_HerbShop
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object LavaridgeTown_HerbShop_EventScript_Clerk : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(LavaridgeTown_HerbShop.WelcomeToHerbShop)
    ctx.pokemart(Items.ENERGYPOWDER, Items.ENERGY_ROOT, Items.HEAL_POWDER, Items.REVIVAL_HERB)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lock
 * faceplayer
 * goto_if_set FLAG_RECEIVED_CHARCOAL, LavaridgeTown_HerbShop_EventScript_ExplainCharcoal
 * msgbox LavaridgeTown_HerbShop_Text_YouveComeToLookAtHerbalMedicine, MSGBOX_DEFAULT
 * giveitem ITEM_CHARCOAL
 * goto_if_eq VAR_RESULT, FALSE, Common_EventScript_ShowBagIsFull
 * setflag FLAG_RECEIVED_CHARCOAL
 * release
 * end
 * ```
 */
internal object LavaridgeTown_HerbShop_EventScript_OldMan : Script {
  override suspend fun run(ctx: ScriptContext) =
      TODO("port LavaridgeTown_HerbShop_EventScript_OldMan")
}

internal object LavaridgeTown_HerbShop_EventScript_ExpertM : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LavaridgeTown_HerbShop.HerbalMedicineWorksButMonWillDislike)
}

internal val LavaridgeTown_HerbShopScripts: Map<String, Script> =
    mapOf(
        "LavaridgeTown_HerbShop_EventScript_Clerk" to LavaridgeTown_HerbShop_EventScript_Clerk,
        "LavaridgeTown_HerbShop_EventScript_OldMan" to LavaridgeTown_HerbShop_EventScript_OldMan,
        "LavaridgeTown_HerbShop_EventScript_ExpertM" to LavaridgeTown_HerbShop_EventScript_ExpertM,
    )
