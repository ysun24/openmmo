package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.LavaridgeTown
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object LavaridgeTown_EventScript_ExpertF : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(LavaridgeTown.OhYouLikeHotSprings)
}

internal object LavaridgeTown_EventScript_ExpertM : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(LavaridgeTown.HotSpringsNeverRunDry)
}

internal object LavaridgeTown_EventScript_OldMan : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(LavaridgeTown.PokemonNippedBackside)
}

internal object LavaridgeTown_EventScript_Twin : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(LavaridgeTown.BatheInHotSpringsEveryDay)
}

internal object LavaridgeTown_EventScript_HotSpringsOldWoman1 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(LavaridgeTown.IfPokemonInHotSprings)
}

internal object LavaridgeTown_EventScript_HotSpringsOldWoman2 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(LavaridgeTown.HotSpringsClaims)
}

internal object LavaridgeTown_EventScript_HerbShopSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(LavaridgeTown.HerbShopSign)
}

internal object LavaridgeTown_EventScript_GymSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(LavaridgeTown.GymSign)
}

internal object LavaridgeTown_EventScript_TownSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(LavaridgeTown.TownSign)
}

internal val LavaridgeTownScripts: Map<String, Script> =
    mapOf(
        "LavaridgeTown_EventScript_ExpertF" to LavaridgeTown_EventScript_ExpertF,
        "LavaridgeTown_EventScript_ExpertM" to LavaridgeTown_EventScript_ExpertM,
        "LavaridgeTown_EventScript_OldMan" to LavaridgeTown_EventScript_OldMan,
        "LavaridgeTown_EventScript_Twin" to LavaridgeTown_EventScript_Twin,
        "LavaridgeTown_EventScript_HotSpringsOldWoman1" to
            LavaridgeTown_EventScript_HotSpringsOldWoman1,
        "LavaridgeTown_EventScript_HotSpringsOldWoman2" to
            LavaridgeTown_EventScript_HotSpringsOldWoman2,
        "LavaridgeTown_EventScript_HerbShopSign" to LavaridgeTown_EventScript_HerbShopSign,
        "LavaridgeTown_EventScript_GymSign" to LavaridgeTown_EventScript_GymSign,
        "LavaridgeTown_EventScript_TownSign" to LavaridgeTown_EventScript_TownSign,
    )
