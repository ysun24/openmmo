package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.CinnabarIsland_Mart
import de.fiereu.openmmo.dialog.generated.kanto.Misc
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object CinnabarIsland_Mart_EventScript_Clerk : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.Text_MayIHelpYou)
    ctx.pokemart(
        Items.ULTRA_BALL,
        Items.GREAT_BALL,
        Items.HYPER_POTION,
        Items.REVIVE,
        Items.FULL_HEAL,
        Items.ESCAPE_ROPE,
        Items.MAX_REPEL)
  }
}

internal object CinnabarIsland_Mart_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(CinnabarIsland_Mart.DontTheyHaveXAttack)
}

internal object CinnabarIsland_Mart_EventScript_Scientist : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(CinnabarIsland_Mart.ExtraItemsNeverHurt)
}

internal val CinnabarIsland_MartScripts: Map<String, Script> =
    mapOf(
        "CinnabarIsland_Mart_EventScript_Clerk" to CinnabarIsland_Mart_EventScript_Clerk,
        "CinnabarIsland_Mart_EventScript_Woman" to CinnabarIsland_Mart_EventScript_Woman,
        "CinnabarIsland_Mart_EventScript_Scientist" to CinnabarIsland_Mart_EventScript_Scientist,
    )
