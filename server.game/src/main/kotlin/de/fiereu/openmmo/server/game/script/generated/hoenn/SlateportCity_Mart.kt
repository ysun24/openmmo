package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Misc
import de.fiereu.openmmo.dialog.generated.hoenn.SlateportCity_Mart
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object SlateportCity_Mart_EventScript_Clerk : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.gText_HowMayIServeYou)
    ctx.pokemart(
        Items.POKE_BALL,
        Items.GREAT_BALL,
        Items.POTION,
        Items.SUPER_POTION,
        Items.ANTIDOTE,
        Items.PARLYZ_HEAL,
        Items.ESCAPE_ROPE,
        Items.REPEL)
  }
}

internal object SlateportCity_Mart_EventScript_BlackBelt : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(SlateportCity_Mart.SomeItemsOnlyAtMart)
}

internal object SlateportCity_Mart_EventScript_Man : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(SlateportCity_Mart.GreatBallIsBetter)
}

internal val SlateportCity_MartScripts: Map<String, Script> =
    mapOf(
        "SlateportCity_Mart_EventScript_Clerk" to SlateportCity_Mart_EventScript_Clerk,
        "SlateportCity_Mart_EventScript_BlackBelt" to SlateportCity_Mart_EventScript_BlackBelt,
        "SlateportCity_Mart_EventScript_Man" to SlateportCity_Mart_EventScript_Man,
    )
