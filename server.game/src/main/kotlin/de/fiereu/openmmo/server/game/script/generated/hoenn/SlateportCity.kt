package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Misc
import de.fiereu.openmmo.dialog.generated.hoenn.SlateportCity
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext
import de.fiereu.openmmo.story.generated.hoenn.HoennFlags

internal object SlateportCity_EventScript_Ty : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SlateportCity.BigSmileForCamera)
}

internal object SlateportCity_EventScript_Gabby : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SlateportCity.MostInvaluableExperience)
}

internal object SlateportCity_EventScript_Sailor1 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(SlateportCity.SeaIsSoWet)
}

internal object SlateportCity_EventScript_Sailor2 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(SlateportCity.SinkOldBoats)
}

internal object SlateportCity_EventScript_PokefanF : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(SlateportCity.BuyTooMuch)
}

internal object SlateportCity_EventScript_Man2 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(SlateportCity.BattleTentBuiltRecently)
}

internal object SlateportCity_EventScript_Woman2 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(SlateportCity.CantChangeTradeMonName)
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lock
 * faceplayer
 * goto_if_unset FLAG_RECEIVED_SECRET_POWER, SlateportCity_EventScript_ComeBackWithSecretPower
 * message gText_HowMayIServeYou
 * waitmessage
 * pokemartdecoration SlateportCity_PokemartDecor
 * msgbox gText_PleaseComeAgain, MSGBOX_DEFAULT
 * release
 * end
 * ```
 */
internal object SlateportCity_EventScript_DecorClerk : Script {
  override suspend fun run(ctx: ScriptContext) {
    // gText_ComeBackWithSecretPower has no ROM offset in our dialog table, so it goes as a notice.
    if (!ctx.isFlagSet(HoennFlags.FLAG_RECEIVED_SECRET_POWER)) {
      ctx.progress?.tell(ctx.session, "Please come back when you have SECRET POWER.")
      return
    }
    ctx.say(Misc.gText_HowMayIServeYou)
    ctx.decorationShop("SlateportCity_PokemartDecor")
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lock
 * faceplayer
 * message gText_HowMayIServeYou
 * waitmessage
 * pokemartdecoration SlateportCity_PokemartDecor_Dolls
 * msgbox gText_PleaseComeAgain, MSGBOX_DEFAULT
 * release
 * end
 * ```
 */
internal object SlateportCity_EventScript_DollClerk : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.gText_HowMayIServeYou)
    ctx.decorationShop("SlateportCity_PokemartDecor_Dolls")
  }
}

internal object SlateportCity_EventScript_Man3 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(SlateportCity.WonderIfLighthouseStartlesPokemon)
}

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object SlateportCity_EventScript_PowerTMClerk : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.gText_HowMayIServeYou)
    ctx.pokemart(Items.TM10, Items.TM43)
  }
}

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object SlateportCity_EventScript_EnergyGuru : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(SlateportCity.EnergyGuruSellWhatYouNeed)
    ctx.pokemart(Items.PROTEIN, Items.IRON, Items.CARBOS, Items.ZINC, Items.CALCIUM, Items.HP_UP)
  }
}

internal object SlateportCity_EventScript_AquaGrunt10 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SlateportCity.ShouldveBroughtMyGameBoy)
}

internal object SlateportCity_EventScript_NameRatersHouseSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SlateportCity.NameRatersHouseSign)
}

internal object SlateportCity_EventScript_MarketSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SlateportCity.MarketSign)
}

internal object SlateportCity_EventScript_OceanicMuseumSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SlateportCity.OceanicMuseumSign)
}

internal object SlateportCity_EventScript_CitySign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SlateportCity.CitySign)
}

internal object SlateportCity_EventScript_PokemonFanClubSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SlateportCity.PokemonFanClubSign)
}

internal object SlateportCity_EventScript_BattleTentSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SlateportCity.BattleTentSign)
}

internal val SlateportCityScripts: Map<String, Script> =
    mapOf(
        "SlateportCity_EventScript_Ty" to SlateportCity_EventScript_Ty,
        "SlateportCity_EventScript_Gabby" to SlateportCity_EventScript_Gabby,
        "SlateportCity_EventScript_Sailor1" to SlateportCity_EventScript_Sailor1,
        "SlateportCity_EventScript_Sailor2" to SlateportCity_EventScript_Sailor2,
        "SlateportCity_EventScript_PokefanF" to SlateportCity_EventScript_PokefanF,
        "SlateportCity_EventScript_Man2" to SlateportCity_EventScript_Man2,
        "SlateportCity_EventScript_Woman2" to SlateportCity_EventScript_Woman2,
        "SlateportCity_EventScript_DecorClerk" to SlateportCity_EventScript_DecorClerk,
        "SlateportCity_EventScript_DollClerk" to SlateportCity_EventScript_DollClerk,
        "SlateportCity_EventScript_Man3" to SlateportCity_EventScript_Man3,
        "SlateportCity_EventScript_PowerTMClerk" to SlateportCity_EventScript_PowerTMClerk,
        "SlateportCity_EventScript_EnergyGuru" to SlateportCity_EventScript_EnergyGuru,
        "SlateportCity_EventScript_AquaGrunt10" to SlateportCity_EventScript_AquaGrunt10,
        "SlateportCity_EventScript_NameRatersHouseSign" to
            SlateportCity_EventScript_NameRatersHouseSign,
        "SlateportCity_EventScript_MarketSign" to SlateportCity_EventScript_MarketSign,
        "SlateportCity_EventScript_OceanicMuseumSign" to
            SlateportCity_EventScript_OceanicMuseumSign,
        "SlateportCity_EventScript_CitySign" to SlateportCity_EventScript_CitySign,
        "SlateportCity_EventScript_PokemonFanClubSign" to
            SlateportCity_EventScript_PokemonFanClubSign,
        "SlateportCity_EventScript_BattleTentSign" to SlateportCity_EventScript_BattleTentSign,
    )
