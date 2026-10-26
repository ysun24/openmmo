package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Misc
import de.fiereu.openmmo.dialog.generated.hoenn.MossdeepCity_Mart
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object MossdeepCity_Mart_EventScript_Clerk : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.gText_HowMayIServeYou)
    ctx.pokemart(
        Items.ULTRA_BALL,
        Items.NET_BALL,
        Items.DIVE_BALL,
        Items.HYPER_POTION,
        Items.FULL_HEAL,
        Items.REVIVE,
        Items.MAX_REPEL,
        Items.X_ATTACK,
        Items.X_DEFEND)
  }
}

internal object MossdeepCity_Mart_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(MossdeepCity_Mart.ReviveIsFantastic)
}

internal object MossdeepCity_Mart_EventScript_Boy : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(MossdeepCity_Mart.MaxRepelLastsLongest)
}

internal object MossdeepCity_Mart_EventScript_Sailor : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(MossdeepCity_Mart.NetAndDiveBallsRare)
}

internal val MossdeepCity_MartScripts: Map<String, Script> =
    mapOf(
        "MossdeepCity_Mart_EventScript_Clerk" to MossdeepCity_Mart_EventScript_Clerk,
        "MossdeepCity_Mart_EventScript_Woman" to MossdeepCity_Mart_EventScript_Woman,
        "MossdeepCity_Mart_EventScript_Boy" to MossdeepCity_Mart_EventScript_Boy,
        "MossdeepCity_Mart_EventScript_Sailor" to MossdeepCity_Mart_EventScript_Sailor,
    )
