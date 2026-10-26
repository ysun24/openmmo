package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.LilycoveCity_DepartmentStore_2F
import de.fiereu.openmmo.dialog.generated.hoenn.Misc
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object LilycoveCity_DepartmentStore_2F_EventScript_Cook : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_DepartmentStore_2F.LearnToUseItemsProperly)
}

internal object LilycoveCity_DepartmentStore_2F_EventScript_PokefanF : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_DepartmentStore_2F.GoodGiftForHusband)
}

internal object LilycoveCity_DepartmentStore_2F_EventScript_Sailor : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_DepartmentStore_2F.StockUpOnItems)
}

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object LilycoveCity_DepartmentStore_2F_EventScript_ClerkRight : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.gText_HowMayIServeYou)
    ctx.pokemart(
        Items.POTION,
        Items.SUPER_POTION,
        Items.HYPER_POTION,
        Items.MAX_POTION,
        Items.REVIVE,
        Items.REPEL,
        Items.SUPER_REPEL,
        Items.MAX_REPEL)
  }
}

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object LilycoveCity_DepartmentStore_2F_EventScript_ClerkLeft : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.gText_HowMayIServeYou)
    ctx.pokemart(
        Items.POKE_BALL,
        Items.GREAT_BALL,
        Items.ULTRA_BALL,
        Items.ESCAPE_ROPE,
        Items.FULL_HEAL,
        Items.ANTIDOTE,
        Items.PARLYZ_HEAL,
        Items.BURN_HEAL,
        Items.ICE_HEAL,
        Items.AWAKENING,
        Items.FLUFFY_TAIL)
  }
}

internal val LilycoveCity_DepartmentStore_2FScripts: Map<String, Script> =
    mapOf(
        "LilycoveCity_DepartmentStore_2F_EventScript_Cook" to
            LilycoveCity_DepartmentStore_2F_EventScript_Cook,
        "LilycoveCity_DepartmentStore_2F_EventScript_PokefanF" to
            LilycoveCity_DepartmentStore_2F_EventScript_PokefanF,
        "LilycoveCity_DepartmentStore_2F_EventScript_Sailor" to
            LilycoveCity_DepartmentStore_2F_EventScript_Sailor,
        "LilycoveCity_DepartmentStore_2F_EventScript_ClerkRight" to
            LilycoveCity_DepartmentStore_2F_EventScript_ClerkRight,
        "LilycoveCity_DepartmentStore_2F_EventScript_ClerkLeft" to
            LilycoveCity_DepartmentStore_2F_EventScript_ClerkLeft,
    )
