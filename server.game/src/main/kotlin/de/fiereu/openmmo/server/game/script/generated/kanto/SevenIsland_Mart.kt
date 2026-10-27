package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Misc
import de.fiereu.openmmo.dialog.generated.kanto.SevenIsland_Mart
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object SevenIsland_Mart_EventScript_Clerk : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.Text_MayIHelpYou)
    ctx.pokemart(
        Items.ULTRA_BALL,
        Items.GREAT_BALL,
        Items.FULL_RESTORE,
        Items.MAX_POTION,
        Items.HYPER_POTION,
        Items.REVIVE,
        Items.FULL_HEAL,
        Items.ESCAPE_ROPE,
        Items.MAX_REPEL)
  }
}

internal object SevenIsland_Mart_EventScript_Lass : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(SevenIsland_Mart.MonHavePersonalitiesOfTheirOwn)
}

internal object SevenIsland_Mart_EventScript_Fisher : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(SevenIsland_Mart.NeedToFishOnSevenIsland)
}

internal object SevenIsland_Mart_EventScript_Hiker : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(SevenIsland_Mart.PreparationsCompleteForRuins)
}

internal val SevenIsland_MartScripts: Map<String, Script> =
    mapOf(
        "SevenIsland_Mart_EventScript_Clerk" to SevenIsland_Mart_EventScript_Clerk,
        "SevenIsland_Mart_EventScript_Lass" to SevenIsland_Mart_EventScript_Lass,
        "SevenIsland_Mart_EventScript_Fisher" to SevenIsland_Mart_EventScript_Fisher,
        "SevenIsland_Mart_EventScript_Hiker" to SevenIsland_Mart_EventScript_Hiker,
    )
