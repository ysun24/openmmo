package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Misc
import de.fiereu.openmmo.dialog.generated.kanto.VermilionCity_Mart
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object VermilionCity_Mart_EventScript_Clerk : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.Text_MayIHelpYou)
    ctx.pokemart(
        Items.POKE_BALL,
        Items.SUPER_POTION,
        Items.ANTIDOTE,
        Items.PARLYZ_HEAL,
        Items.AWAKENING,
        Items.ICE_HEAL,
        Items.REPEL)
  }
}

internal object VermilionCity_Mart_EventScript_CooltrainerF : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(VermilionCity_Mart.MonsGoodOrBadDependingOnTrainer)
}

internal object VermilionCity_Mart_EventScript_BaldingMan : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(VermilionCity_Mart.TeamRocketAreWickedPeople)
}

internal val VermilionCity_MartScripts: Map<String, Script> =
    mapOf(
        "VermilionCity_Mart_EventScript_Clerk" to VermilionCity_Mart_EventScript_Clerk,
        "VermilionCity_Mart_EventScript_CooltrainerF" to
            VermilionCity_Mart_EventScript_CooltrainerF,
        "VermilionCity_Mart_EventScript_BaldingMan" to VermilionCity_Mart_EventScript_BaldingMan,
    )
