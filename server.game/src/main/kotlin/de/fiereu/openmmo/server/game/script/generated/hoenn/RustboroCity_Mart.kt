package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Misc
import de.fiereu.openmmo.dialog.generated.hoenn.RustboroCity_Mart
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext
import de.fiereu.openmmo.story.generated.hoenn.HoennFlags

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object RustboroCity_Mart_EventScript_Clerk : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.gText_HowMayIServeYou)
    if (!ctx.isFlagSet(HoennFlags.FLAG_MET_DEVON_EMPLOYEE)) {
      ctx.pokemart(
          Items.POKE_BALL,
          Items.POTION,
          Items.SUPER_POTION,
          Items.ANTIDOTE,
          Items.PARLYZ_HEAL,
          Items.ESCAPE_ROPE,
          Items.REPEL,
          Items.X_SPEED,
          Items.X_ATTACK,
          Items.X_DEFEND)
      return
    }
    if (ctx.isFlagSet(HoennFlags.FLAG_MET_DEVON_EMPLOYEE)) {
      ctx.pokemart(
          Items.POKE_BALL,
          Items.TIMER_BALL,
          Items.REPEAT_BALL,
          Items.POTION,
          Items.SUPER_POTION,
          Items.ANTIDOTE,
          Items.PARLYZ_HEAL,
          Items.ESCAPE_ROPE,
          Items.REPEL,
          Items.X_SPEED,
          Items.X_ATTACK,
          Items.X_DEFEND)
      return
    }
  }
}

internal object RustboroCity_Mart_EventScript_Boy : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(RustboroCity_Mart.ShouldBuySuperPotionsInstead)
}

internal object RustboroCity_Mart_EventScript_PokefanF : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(RustboroCity_Mart.BuyingHealsInCaseOfShroomish)
}

internal object RustboroCity_Mart_EventScript_BugCatcher : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(RustboroCity_Mart.GettingEscapeRopeJustInCase)
}

internal val RustboroCity_MartScripts: Map<String, Script> =
    mapOf(
        "RustboroCity_Mart_EventScript_Clerk" to RustboroCity_Mart_EventScript_Clerk,
        "RustboroCity_Mart_EventScript_Boy" to RustboroCity_Mart_EventScript_Boy,
        "RustboroCity_Mart_EventScript_PokefanF" to RustboroCity_Mart_EventScript_PokefanF,
        "RustboroCity_Mart_EventScript_BugCatcher" to RustboroCity_Mart_EventScript_BugCatcher,
    )
