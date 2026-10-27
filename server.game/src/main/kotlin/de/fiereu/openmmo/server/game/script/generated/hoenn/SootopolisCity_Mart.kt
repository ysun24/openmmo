package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Misc
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object SootopolisCity_Mart_EventScript_Clerk : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.gText_HowMayIServeYou)
    ctx.pokemart(
        Items.ULTRA_BALL,
        Items.HYPER_POTION,
        Items.MAX_POTION,
        Items.FULL_HEAL,
        Items.REVIVE,
        Items.MAX_REPEL,
        Items.X_ATTACK,
        Items.X_DEFEND)
  }
}

internal val SootopolisCity_MartScripts: Map<String, Script> =
    mapOf(
        "SootopolisCity_Mart_EventScript_Clerk" to SootopolisCity_Mart_EventScript_Clerk,
    )
