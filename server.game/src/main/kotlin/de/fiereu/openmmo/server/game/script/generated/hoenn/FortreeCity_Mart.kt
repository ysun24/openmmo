package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.FortreeCity_Mart
import de.fiereu.openmmo.dialog.generated.hoenn.Misc
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object FortreeCity_Mart_EventScript_Clerk : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.gText_HowMayIServeYou)
    ctx.pokemart(
        Items.GREAT_BALL,
        Items.ULTRA_BALL,
        Items.SUPER_POTION,
        Items.HYPER_POTION,
        Items.ANTIDOTE,
        Items.PARLYZ_HEAL,
        Items.AWAKENING,
        Items.REVIVE,
        Items.SUPER_REPEL)
  }
}

internal object FortreeCity_Mart_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(FortreeCity_Mart.SuperRepelBetter)
}

internal object FortreeCity_Mart_EventScript_Girl : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(FortreeCity_Mart.StockUpOnItems)
}

internal object FortreeCity_Mart_EventScript_Boy : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(FortreeCity_Mart.RareCandyMakesMonGrow)
}

internal val FortreeCity_MartScripts: Map<String, Script> =
    mapOf(
        "FortreeCity_Mart_EventScript_Clerk" to FortreeCity_Mart_EventScript_Clerk,
        "FortreeCity_Mart_EventScript_Woman" to FortreeCity_Mart_EventScript_Woman,
        "FortreeCity_Mart_EventScript_Girl" to FortreeCity_Mart_EventScript_Girl,
        "FortreeCity_Mart_EventScript_Boy" to FortreeCity_Mart_EventScript_Boy,
    )
