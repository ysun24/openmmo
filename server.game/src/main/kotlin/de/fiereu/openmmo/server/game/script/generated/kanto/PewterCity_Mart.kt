package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Misc
import de.fiereu.openmmo.dialog.generated.kanto.PewterCity_Mart
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object PewterCity_Mart_EventScript_Youngster : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(PewterCity_Mart.BoughtWeirdFishFromShadyGuy)
}

internal object PewterCity_Mart_EventScript_Boy : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(PewterCity_Mart.GoodThingsIfRaiseMonsDiligently)
}

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object PewterCity_Mart_EventScript_Clerk : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.Text_MayIHelpYou)
    ctx.pokemart(
        Items.POKE_BALL,
        Items.POTION,
        Items.ANTIDOTE,
        Items.PARLYZ_HEAL,
        Items.AWAKENING,
        Items.BURN_HEAL,
        Items.ESCAPE_ROPE,
        Items.REPEL)
  }
}

internal val PewterCity_MartScripts: Map<String, Script> =
    mapOf(
        "PewterCity_Mart_EventScript_Youngster" to PewterCity_Mart_EventScript_Youngster,
        "PewterCity_Mart_EventScript_Boy" to PewterCity_Mart_EventScript_Boy,
        "PewterCity_Mart_EventScript_Clerk" to PewterCity_Mart_EventScript_Clerk,
    )
