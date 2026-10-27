package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Misc
import de.fiereu.openmmo.dialog.generated.kanto.ThreeIsland_Mart
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object ThreeIsland_Mart_EventScript_Clerk : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.Text_MayIHelpYou)
    ctx.pokemart(
        Items.ULTRA_BALL,
        Items.HYPER_POTION,
        Items.REVIVE,
        Items.FULL_HEAL,
        Items.ESCAPE_ROPE,
        Items.MAX_REPEL)
  }
}

internal object ThreeIsland_Mart_EventScript_Picnicker : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(ThreeIsland_Mart.TrueThatCeldadonDeptStoreBigger)
}

internal object ThreeIsland_Mart_EventScript_BugCatcher : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(ThreeIsland_Mart.PeopleHealWithBerriesFromForest)
}

internal object ThreeIsland_Mart_EventScript_Youngster : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(ThreeIsland_Mart.BikersWereAboutToTrashMart)
}

internal val ThreeIsland_MartScripts: Map<String, Script> =
    mapOf(
        "ThreeIsland_Mart_EventScript_Clerk" to ThreeIsland_Mart_EventScript_Clerk,
        "ThreeIsland_Mart_EventScript_Picnicker" to ThreeIsland_Mart_EventScript_Picnicker,
        "ThreeIsland_Mart_EventScript_BugCatcher" to ThreeIsland_Mart_EventScript_BugCatcher,
        "ThreeIsland_Mart_EventScript_Youngster" to ThreeIsland_Mart_EventScript_Youngster,
    )
