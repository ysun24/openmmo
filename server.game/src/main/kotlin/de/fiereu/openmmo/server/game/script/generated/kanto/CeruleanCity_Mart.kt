package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.CeruleanCity_Mart
import de.fiereu.openmmo.dialog.generated.kanto.Misc
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object CeruleanCity_Mart_EventScript_Clerk : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.Text_MayIHelpYou)
    ctx.pokemart(
        Items.POKE_BALL,
        Items.SUPER_POTION,
        Items.POTION,
        Items.ANTIDOTE,
        Items.PARLYZ_HEAL,
        Items.AWAKENING,
        Items.BURN_HEAL,
        Items.ESCAPE_ROPE,
        Items.REPEL)
  }
}

internal object CeruleanCity_Mart_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(CeruleanCity_Mart.DoYouKnowAboutRareCandy)
}

internal object CeruleanCity_Mart_EventScript_Youngster : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(CeruleanCity_Mart.RepelWorksOnWeakMons)
}

internal val CeruleanCity_MartScripts: Map<String, Script> =
    mapOf(
        "CeruleanCity_Mart_EventScript_Clerk" to CeruleanCity_Mart_EventScript_Clerk,
        "CeruleanCity_Mart_EventScript_Woman" to CeruleanCity_Mart_EventScript_Woman,
        "CeruleanCity_Mart_EventScript_Youngster" to CeruleanCity_Mart_EventScript_Youngster,
    )
