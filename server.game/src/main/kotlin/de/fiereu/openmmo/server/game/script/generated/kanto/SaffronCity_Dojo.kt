package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.SaffronCity_Dojo
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SaffronCity_Dojo_EventScript_Statue : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SaffronCity_Dojo.FightingDojo)
}

internal object SaffronCity_Dojo_EventScript_LeftScroll : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SaffronCity_Dojo.EnemiesOnEverySide)
}

internal object SaffronCity_Dojo_EventScript_RightScroll : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SaffronCity_Dojo.GoesAroundComesAround)
}

internal val SaffronCity_DojoScripts: Map<String, Script> =
    mapOf(
        "SaffronCity_Dojo_EventScript_Statue" to SaffronCity_Dojo_EventScript_Statue,
        "SaffronCity_Dojo_EventScript_LeftScroll" to SaffronCity_Dojo_EventScript_LeftScroll,
        "SaffronCity_Dojo_EventScript_RightScroll" to SaffronCity_Dojo_EventScript_RightScroll,
    )
