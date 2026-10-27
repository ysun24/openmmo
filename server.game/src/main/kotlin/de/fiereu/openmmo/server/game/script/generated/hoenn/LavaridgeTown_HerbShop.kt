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

internal object LavaridgeTown_HerbShop_EventScript_ExpertM : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LavaridgeTown_HerbShop.HerbalMedicineWorksButMonWillDislike)
}

internal val LavaridgeTown_HerbShopScripts: Map<String, Script> =
    mapOf(
        "LavaridgeTown_HerbShop_EventScript_Clerk" to LavaridgeTown_HerbShop_EventScript_Clerk,
        "LavaridgeTown_HerbShop_EventScript_ExpertM" to LavaridgeTown_HerbShop_EventScript_ExpertM,
    )
