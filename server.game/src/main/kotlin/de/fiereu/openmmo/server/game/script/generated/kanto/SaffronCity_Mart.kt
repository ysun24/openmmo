package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Misc
import de.fiereu.openmmo.dialog.generated.kanto.SaffronCity_Mart
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object SaffronCity_Mart_EventScript_Clerk : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.Text_MayIHelpYou)
    ctx.pokemart(
        Items.GREAT_BALL,
        Items.HYPER_POTION,
        Items.REVIVE,
        Items.FULL_HEAL,
        Items.ESCAPE_ROPE,
        Items.MAX_REPEL)
  }
}

internal object SaffronCity_Mart_EventScript_Youngster : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(SaffronCity_Mart.MaxRepelMoreEffectiveThanSuper)
}

internal object SaffronCity_Mart_EventScript_Lass : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(SaffronCity_Mart.ReviveIsCostly)
}

internal val SaffronCity_MartScripts: Map<String, Script> =
    mapOf(
        "SaffronCity_Mart_EventScript_Clerk" to SaffronCity_Mart_EventScript_Clerk,
        "SaffronCity_Mart_EventScript_Youngster" to SaffronCity_Mart_EventScript_Youngster,
        "SaffronCity_Mart_EventScript_Lass" to SaffronCity_Mart_EventScript_Lass,
    )
