package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.LavaridgeTown_Mart
import de.fiereu.openmmo.dialog.generated.hoenn.Misc
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object LavaridgeTown_Mart_EventScript_Clerk : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.gText_HowMayIServeYou)
    ctx.pokemart(
        Items.GREAT_BALL,
        Items.SUPER_POTION,
        Items.ANTIDOTE,
        Items.PARLYZ_HEAL,
        Items.AWAKENING,
        Items.BURN_HEAL,
        Items.REVIVE,
        Items.SUPER_REPEL,
        Items.X_SPEED)
  }
}

internal object LavaridgeTown_Mart_EventScript_ExpertM : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(LavaridgeTown_Mart.XSpeedFirstStrike)
}

internal object LavaridgeTown_Mart_EventScript_OldWoman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LavaridgeTown_Mart.LocalSpecialtyOnMtChimney)
}

internal val LavaridgeTown_MartScripts: Map<String, Script> =
    mapOf(
        "LavaridgeTown_Mart_EventScript_Clerk" to LavaridgeTown_Mart_EventScript_Clerk,
        "LavaridgeTown_Mart_EventScript_ExpertM" to LavaridgeTown_Mart_EventScript_ExpertM,
        "LavaridgeTown_Mart_EventScript_OldWoman" to LavaridgeTown_Mart_EventScript_OldWoman,
    )
