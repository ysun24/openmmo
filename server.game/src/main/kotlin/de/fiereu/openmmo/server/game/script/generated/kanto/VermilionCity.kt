package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.VermilionCity
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object VermilionCity_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(VermilionCity.GrimerMultipliesInSludge)
}

internal object VermilionCity_EventScript_OldMan2 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(VermilionCity.BuildingOnThisLand)
}

internal object VermilionCity_EventScript_Sailor : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(VermilionCity.SSAnneVisitsOnceAYear)
}

internal object VermilionCity_EventScript_CitySign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(VermilionCity.CitySign)
}

internal object VermilionCity_EventScript_PokemonFanClubSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(VermilionCity.PokemonFanClubSign)
}

internal object VermilionCity_EventScript_HarborSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(VermilionCity.VermilionHarbor)
}

internal object VermilionCity_EventScript_SnorlaxNotice : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(VermilionCity.SnorlaxBlockingRoute12)
}

internal val VermilionCityScripts: Map<String, Script> =
    mapOf(
        "VermilionCity_EventScript_Woman" to VermilionCity_EventScript_Woman,
        "VermilionCity_EventScript_OldMan2" to VermilionCity_EventScript_OldMan2,
        "VermilionCity_EventScript_Sailor" to VermilionCity_EventScript_Sailor,
        "VermilionCity_EventScript_CitySign" to VermilionCity_EventScript_CitySign,
        "VermilionCity_EventScript_PokemonFanClubSign" to
            VermilionCity_EventScript_PokemonFanClubSign,
        "VermilionCity_EventScript_HarborSign" to VermilionCity_EventScript_HarborSign,
        "VermilionCity_EventScript_SnorlaxNotice" to VermilionCity_EventScript_SnorlaxNotice,
    )
