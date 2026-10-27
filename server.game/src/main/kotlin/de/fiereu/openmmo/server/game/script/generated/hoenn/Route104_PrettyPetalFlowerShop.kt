package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Misc
import de.fiereu.openmmo.dialog.generated.hoenn.Route104_PrettyPetalFlowerShop
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.story.generated.hoenn.HoennFlags
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext
import java.time.LocalDate

/**
 * Not ported yet. Decomp body:
 * ```
 * lock
 * faceplayer
 * goto_if_set FLAG_TEMP_1, Route104_PrettyPetalFlowerShop_EventScript_SellDecorations
 * msgbox Route104_PrettyPetalFlowerShop_Text_ThisIsPrettyPetalFlowerShop, MSGBOX_DEFAULT
 * goto_if_set FLAG_MET_PRETTY_PETAL_SHOP_OWNER, Route104_PrettyPetalFlowerShop_EventScript_AlreadyMet
 * setflag FLAG_MET_PRETTY_PETAL_SHOP_OWNER
 * msgbox Route104_PrettyPetalFlowerShop_Text_IntroLearnAboutBerries, MSGBOX_YESNO
 * call_if_eq VAR_RESULT, YES, Route104_PrettyPetalFlowerShop_EventScript_ExplainBerries
 * call_if_eq VAR_RESULT, NO, Route104_PrettyPetalFlowerShop_EventScript_DontExplainBerries
 * release
 * end
 * ```
 */
internal object Route104_PrettyPetalFlowerShop_EventScript_ShopOwner : Script {
  override suspend fun run(ctx: ScriptContext) {
    // The transition script sets FLAG_TEMP_1 once the owner was met and the third badge is in.
    if (ctx.isFlagSet(HoennFlags.FLAG_MET_PRETTY_PETAL_SHOP_OWNER) &&
        ctx.isFlagSet(HoennFlags.FLAG_BADGE03_GET)) {
      ctx.say(Misc.gText_PlayerWhatCanIDoForYou)
      ctx.decorationShop("Route104_PrettyPetalFlowerShop_Pokemart_Plants")
      return
    }
    ctx.say(Route104_PrettyPetalFlowerShop.ThisIsPrettyPetalFlowerShop)
    val learn =
        if (ctx.isFlagSet(HoennFlags.FLAG_MET_PRETTY_PETAL_SHOP_OWNER)) {
          ctx.askYesNo(Route104_PrettyPetalFlowerShop.LearnAboutBerries)
        } else {
          ctx.setFlag(HoennFlags.FLAG_MET_PRETTY_PETAL_SHOP_OWNER)
          ctx.askYesNo(Route104_PrettyPetalFlowerShop.IntroLearnAboutBerries)
        }
    ctx.say(if (learn) Route104_PrettyPetalFlowerShop.BerriesExplanation else Route104_PrettyPetalFlowerShop.FlowersBringHappiness)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lock
 * faceplayer
 * goto_if_unset FLAG_RECEIVED_WAILMER_PAIL, Route104_PrettyPetalFlowerShop_EventScript_GiveWailmerPail
 * msgbox Route104_PrettyPetalFlowerShop_Text_WailmerPailExplanation, MSGBOX_DEFAULT
 * release
 * end
 * ```
 */
internal object Route104_PrettyPetalFlowerShop_EventScript_WailmerPailGirl : Script {
  override suspend fun run(ctx: ScriptContext) {
    if (!ctx.isFlagSet(HoennFlags.FLAG_RECEIVED_WAILMER_PAIL)) {
      ctx.say(Route104_PrettyPetalFlowerShop.YouCanHaveThis)
      // Gen 5 has no WAILMER PAIL; its watering can is the SPRAYDUCK.
      if (!ctx.giveItem(Items.SPRAYDUCK)) return
      ctx.say(Route104_PrettyPetalFlowerShop.WailmerPailExplanation)
      ctx.setFlag(HoennFlags.FLAG_RECEIVED_WAILMER_PAIL)
      return
    }
    ctx.say(Route104_PrettyPetalFlowerShop.WailmerPailExplanation)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lock
 * faceplayer
 * dotimebasedevents
 * goto_if_set FLAG_DAILY_FLOWER_SHOP_RECEIVED_BERRY, Route104_PrettyPetalFlowerShop_EventScript_AlreadyReceivedBerry
 * msgbox Route104_PrettyPetalFlowerShop_Text_ImGrowingFlowers, MSGBOX_DEFAULT
 * random 8
 * addvar VAR_RESULT, FIRST_BERRY_INDEX
 * giveitem VAR_RESULT
 * goto_if_eq VAR_RESULT, 0, Common_EventScript_ShowBagIsFull
 * setflag FLAG_DAILY_FLOWER_SHOP_RECEIVED_BERRY
 * msgbox Route104_PrettyPetalFlowerShop_Text_MachineMixesBerries, MSGBOX_DEFAULT
 * release
 * end
 * ```
 */
internal object Route104_PrettyPetalFlowerShop_EventScript_RandomBerryGirl : Script {
  override suspend fun run(ctx: ScriptContext) {
    // FLAG_DAILY_FLOWER_SHOP_RECEIVED_BERRY is cleared every day by dotimebasedevents; the day
    // number it was set on stands in for it.
    val today = LocalDate.now().toEpochDay().toInt()
    if (ctx.getVar(DAILY_BERRY_DAY) == today) return ctx.say(Route104_PrettyPetalFlowerShop.MachineMixesBerries)
    ctx.say(Route104_PrettyPetalFlowerShop.ImGrowingFlowers)
    // random 8 + FIRST_BERRY_INDEX: one of the first eight berries.
    val berry = FIRST_EIGHT_BERRIES.random()
    if (!ctx.giveItem(berry)) return
    ctx.setVar(DAILY_BERRY_DAY, today)
    ctx.say(Route104_PrettyPetalFlowerShop.MachineMixesBerries)
  }
}

private const val DAILY_BERRY_DAY = "openmmo/DAILY_FLOWER_SHOP_BERRY_DAY"

private val FIRST_EIGHT_BERRIES =
    listOf(
        Items.CHERI_BERRY,
        Items.CHESTO_BERRY,
        Items.PECHA_BERRY,
        Items.RAWST_BERRY,
        Items.ASPEAR_BERRY,
        Items.LEPPA_BERRY,
        Items.ORAN_BERRY,
        Items.PERSIM_BERRY,
    )

internal val Route104_PrettyPetalFlowerShopScripts: Map<String, Script> =
    mapOf(
        "Route104_PrettyPetalFlowerShop_EventScript_ShopOwner" to
            Route104_PrettyPetalFlowerShop_EventScript_ShopOwner,
        "Route104_PrettyPetalFlowerShop_EventScript_WailmerPailGirl" to
            Route104_PrettyPetalFlowerShop_EventScript_WailmerPailGirl,
        "Route104_PrettyPetalFlowerShop_EventScript_RandomBerryGirl" to
            Route104_PrettyPetalFlowerShop_EventScript_RandomBerryGirl,
    )
