package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Route104
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route104_EventScript_BugCatcher : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route104.WhatsItLikeAtBottomOfSea)
}

internal object Route104_EventScript_Girl1 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route104.BrineyLivesInSeasideCottage)
}

internal object Route104_EventScript_Boy1 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route104.ThrowBallAtWeakenedPokemon)
}

internal object Route104_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route104.OnlyThrowBallAtWildPokemon)
}

internal object Route104_EventScript_Girl2 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route104.ImNotATrainer)
}

internal object Route104_EventScript_BrineysCottageSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route104.MrBrineysCottage)
}

internal object Route104_EventScript_RouteSignPetalburg : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route104.RouteSignPetalburg)
}

internal object Route104_EventScript_RouteSignRustboro : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route104.RouteSignRustboro)
}

internal object Route104_EventScript_FlowerShopSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route104.PrettyPetalFlowShop)
}

internal object Route104_EventScript_TrainerTipsDoubleBattles : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route104.TrainerTipsDoubleBattles)
}

internal val Route104Scripts: Map<String, Script> =
    mapOf(
        "Route104_EventScript_BugCatcher" to Route104_EventScript_BugCatcher,
        "Route104_EventScript_Girl1" to Route104_EventScript_Girl1,
        "Route104_EventScript_Boy1" to Route104_EventScript_Boy1,
        "Route104_EventScript_Woman" to Route104_EventScript_Woman,
        "Route104_EventScript_Girl2" to Route104_EventScript_Girl2,
        "Route104_EventScript_BrineysCottageSign" to Route104_EventScript_BrineysCottageSign,
        "Route104_EventScript_RouteSignPetalburg" to Route104_EventScript_RouteSignPetalburg,
        "Route104_EventScript_RouteSignRustboro" to Route104_EventScript_RouteSignRustboro,
        "Route104_EventScript_FlowerShopSign" to Route104_EventScript_FlowerShopSign,
        "Route104_EventScript_TrainerTipsDoubleBattles" to
            Route104_EventScript_TrainerTipsDoubleBattles,
    )
