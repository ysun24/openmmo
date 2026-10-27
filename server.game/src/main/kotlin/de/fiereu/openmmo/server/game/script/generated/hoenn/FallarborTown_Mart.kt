package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.FallarborTown_Mart
import de.fiereu.openmmo.dialog.generated.hoenn.Misc
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object FallarborTown_Mart_EventScript_Clerk : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.gText_HowMayIServeYou)
    ctx.pokemart(
        Items.GREAT_BALL,
        Items.SUPER_POTION,
        Items.ANTIDOTE,
        Items.PARLYZ_HEAL,
        Items.ESCAPE_ROPE,
        Items.SUPER_REPEL,
        Items.X_SPECIAL,
        Items.X_SPEED,
        Items.X_ATTACK,
        Items.X_DEFEND,
        Items.DIRE_HIT,
        Items.GUARD_SPEC)
  }
}

internal object FallarborTown_Mart_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(FallarborTown_Mart.DecidingSkittyEvolve)
}

internal object FallarborTown_Mart_EventScript_PokefanM : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(FallarborTown_Mart.SellNuggetIFound)
}

internal val FallarborTown_MartScripts: Map<String, Script> =
    mapOf(
        "FallarborTown_Mart_EventScript_Clerk" to FallarborTown_Mart_EventScript_Clerk,
        "FallarborTown_Mart_EventScript_Woman" to FallarborTown_Mart_EventScript_Woman,
        "FallarborTown_Mart_EventScript_PokefanM" to FallarborTown_Mart_EventScript_PokefanM,
    )
