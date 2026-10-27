package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.FourIsland_Mart
import de.fiereu.openmmo.dialog.generated.kanto.Misc
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object FourIsland_Mart_EventScript_Clerk : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.Text_MayIHelpYou)
    ctx.pokemart(
        Items.ULTRA_BALL,
        Items.FULL_RESTORE,
        Items.MAX_POTION,
        Items.REVIVE,
        Items.ICE_HEAL,
        Items.FULL_HEAL,
        Items.ESCAPE_ROPE,
        Items.MAX_REPEL)
  }
}

internal object FourIsland_Mart_EventScript_Camper : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(FourIsland_Mart.IcefallCaveIsFrigid)
}

internal val FourIsland_MartScripts: Map<String, Script> =
    mapOf(
        "FourIsland_Mart_EventScript_Clerk" to FourIsland_Mart_EventScript_Clerk,
        "FourIsland_Mart_EventScript_Camper" to FourIsland_Mart_EventScript_Camper,
    )
