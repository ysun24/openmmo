package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.FuchsiaCity_Mart
import de.fiereu.openmmo.dialog.generated.kanto.Misc
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object FuchsiaCity_Mart_EventScript_Clerk : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.Text_MayIHelpYou)
    ctx.pokemart(
        Items.ULTRA_BALL,
        Items.GREAT_BALL,
        Items.SUPER_POTION,
        Items.REVIVE,
        Items.FULL_HEAL,
        Items.MAX_REPEL)
  }
}

internal object FuchsiaCity_Mart_EventScript_Gentleman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(FuchsiaCity_Mart.DontTheyHaveSafariZonePennants)
}

internal object FuchsiaCity_Mart_EventScript_CooltrainerF : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(FuchsiaCity_Mart.DidYouTryXSpeed)
}

internal val FuchsiaCity_MartScripts: Map<String, Script> =
    mapOf(
        "FuchsiaCity_Mart_EventScript_Clerk" to FuchsiaCity_Mart_EventScript_Clerk,
        "FuchsiaCity_Mart_EventScript_Gentleman" to FuchsiaCity_Mart_EventScript_Gentleman,
        "FuchsiaCity_Mart_EventScript_CooltrainerF" to FuchsiaCity_Mart_EventScript_CooltrainerF,
    )
