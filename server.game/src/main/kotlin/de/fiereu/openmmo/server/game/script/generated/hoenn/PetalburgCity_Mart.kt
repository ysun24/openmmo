package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Misc
import de.fiereu.openmmo.dialog.generated.hoenn.PetalburgCity_Mart
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext
import de.fiereu.openmmo.story.generated.hoenn.HoennFlags

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object PetalburgCity_Mart_EventScript_Clerk : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.gText_HowMayIServeYou)
    if (ctx.isFlagSet(HoennFlags.FLAG_PETALBURG_MART_EXPANDED_ITEMS)) {
      ctx.pokemart(
          Items.POKE_BALL,
          Items.GREAT_BALL,
          Items.POTION,
          Items.SUPER_POTION,
          Items.ANTIDOTE,
          Items.PARLYZ_HEAL,
          Items.AWAKENING,
          Items.ESCAPE_ROPE,
          Items.REPEL,
          Items.X_SPEED,
          Items.X_ATTACK,
          Items.X_DEFEND)
      return
    }
    ctx.pokemart(
        Items.POKE_BALL,
        Items.POTION,
        Items.ANTIDOTE,
        Items.PARLYZ_HEAL,
        Items.AWAKENING,
        Items.ESCAPE_ROPE,
        Items.REPEL,
        Items.X_SPEED,
        Items.X_ATTACK,
        Items.X_DEFEND)
  }
}

internal object PetalburgCity_Mart_EventScript_Man : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(PetalburgCity_Mart.TakeSomeAntidotesWithYou)
}

internal object PetalburgCity_Mart_EventScript_Boy : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(PetalburgCity_Mart.RepelIsUseful)
}

internal object PetalburgCity_Mart_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(PetalburgCity_Mart.WeakWillGrowStronger)
}

internal val PetalburgCity_MartScripts: Map<String, Script> =
    mapOf(
        "PetalburgCity_Mart_EventScript_Clerk" to PetalburgCity_Mart_EventScript_Clerk,
        "PetalburgCity_Mart_EventScript_Man" to PetalburgCity_Mart_EventScript_Man,
        "PetalburgCity_Mart_EventScript_Boy" to PetalburgCity_Mart_EventScript_Boy,
        "PetalburgCity_Mart_EventScript_Woman" to PetalburgCity_Mart_EventScript_Woman,
    )
