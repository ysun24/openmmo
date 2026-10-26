package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Misc
import de.fiereu.openmmo.dialog.generated.kanto.SixIsland_Mart
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object SixIsland_Mart_EventScript_Clerk : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.Text_MayIHelpYou)
    ctx.pokemart(
        Items.ULTRA_BALL,
        Items.FULL_RESTORE,
        Items.MAX_POTION,
        Items.REVIVE,
        Items.FULL_HEAL,
        Items.ESCAPE_ROPE,
        Items.MAX_REPEL)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lock
 * faceplayer
 * famechecker FAMECHECKER_AGATHA, 5
 * msgbox SixIsland_Mart_Text_AgathaOldestEverEliteFourMember
 * release
 * end
 * ```
 */
internal object SixIsland_Mart_EventScript_OldWoman : Script {
  override suspend fun run(ctx: ScriptContext) = TODO("port SixIsland_Mart_EventScript_OldWoman")
}

internal object SixIsland_Mart_EventScript_Picnicker : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(SixIsland_Mart.ShouldBuyMailAndWriteLetter)
}

internal val SixIsland_MartScripts: Map<String, Script> =
    mapOf(
        "SixIsland_Mart_EventScript_Clerk" to SixIsland_Mart_EventScript_Clerk,
        "SixIsland_Mart_EventScript_OldWoman" to SixIsland_Mart_EventScript_OldWoman,
        "SixIsland_Mart_EventScript_Picnicker" to SixIsland_Mart_EventScript_Picnicker,
    )
