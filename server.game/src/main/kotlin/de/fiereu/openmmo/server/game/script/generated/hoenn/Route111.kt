package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Route111
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route111_EventScript_Man1 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route111.ToughToKeepWinningUpTheRanks)
}

internal object Route111_EventScript_Man2 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route111.WinstrateFamilyDestroyedMe)
}

internal object Route111_EventScript_WinstrateHouseSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route111.WinstrateHouseSign)
}

internal object Route111_EventScript_RouteSignMauville : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route111.RouteSignMauville)
}

internal object Route111_EventScript_RouteSign112 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route111.RouteSign112)
}

internal object Route111_EventScript_RouteSign113 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route111.RouteSign113)
}

internal object Route111_EventScript_OldLadysRestStopSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route111.OldLadysRestStopSign)
}

internal object Route111_EventScript_TrainerTipsSpAtkSpDef : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route111.TrainerTipsSpAtkSpDef)
}

internal object Route111_EventScript_TrainerHillSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route111.TrainerHillSign)
}

internal val Route111Scripts: Map<String, Script> =
    mapOf(
        "Route111_EventScript_Man1" to Route111_EventScript_Man1,
        "Route111_EventScript_Man2" to Route111_EventScript_Man2,
        "Route111_EventScript_WinstrateHouseSign" to Route111_EventScript_WinstrateHouseSign,
        "Route111_EventScript_RouteSignMauville" to Route111_EventScript_RouteSignMauville,
        "Route111_EventScript_RouteSign112" to Route111_EventScript_RouteSign112,
        "Route111_EventScript_RouteSign113" to Route111_EventScript_RouteSign113,
        "Route111_EventScript_OldLadysRestStopSign" to Route111_EventScript_OldLadysRestStopSign,
        "Route111_EventScript_TrainerTipsSpAtkSpDef" to Route111_EventScript_TrainerTipsSpAtkSpDef,
        "Route111_EventScript_TrainerHillSign" to Route111_EventScript_TrainerHillSign,
    )
