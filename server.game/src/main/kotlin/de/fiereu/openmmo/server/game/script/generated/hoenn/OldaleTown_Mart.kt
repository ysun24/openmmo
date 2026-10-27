package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Misc
import de.fiereu.openmmo.dialog.generated.hoenn.OldaleTown_Mart
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext
import de.fiereu.openmmo.story.generated.hoenn.HoennFlags

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object OldaleTown_Mart_EventScript_Clerk : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.gText_HowMayIServeYou)
    if (ctx.isFlagSet(HoennFlags.FLAG_ADVENTURE_STARTED)) {
      ctx.pokemart(
          Items.POKE_BALL, Items.POTION, Items.ANTIDOTE, Items.PARLYZ_HEAL, Items.AWAKENING)
      return
    }
    ctx.pokemart(Items.POTION, Items.ANTIDOTE, Items.PARLYZ_HEAL, Items.AWAKENING)
  }
}

internal object OldaleTown_Mart_EventScript_Boy : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(OldaleTown_Mart.RestoreHPWithPotion)
}

internal val OldaleTown_MartScripts: Map<String, Script> =
    mapOf(
        "OldaleTown_Mart_EventScript_Clerk" to OldaleTown_Mart_EventScript_Clerk,
        "OldaleTown_Mart_EventScript_Boy" to OldaleTown_Mart_EventScript_Boy,
    )
