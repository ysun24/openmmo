package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Route109
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route109_EventScript_SeashoreHouseGirl : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route109.ChillAtMyPapasSpot)
}

internal object Route109_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route109.LittleKidsDartAround)
}

internal object Route109_EventScript_OldMan : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route109.ZigzagoonPicksUpLitter)
}

internal object Route109_EventScript_SeashoreHouseSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route109.SeashoreHouseSign)
}

internal object Route109_EventScript_TrainerTipsSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route109.TrainerTipsSign)
}

internal val Route109Scripts: Map<String, Script> =
    mapOf(
        "Route109_EventScript_SeashoreHouseGirl" to Route109_EventScript_SeashoreHouseGirl,
        "Route109_EventScript_Woman" to Route109_EventScript_Woman,
        "Route109_EventScript_OldMan" to Route109_EventScript_OldMan,
        "Route109_EventScript_SeashoreHouseSign" to Route109_EventScript_SeashoreHouseSign,
        "Route109_EventScript_TrainerTipsSign" to Route109_EventScript_TrainerTipsSign,
    )
