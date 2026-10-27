package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.CeladonCity_DepartmentStore_Roof
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object CeladonCity_DepartmentStore_Roof_EventScript_CooltrainerM : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(CeladonCity_DepartmentStore_Roof.MySisterIsImmature)
}

internal object CeladonCity_DepartmentStore_Roof_EventScript_FloorSign : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(CeladonCity_DepartmentStore_Roof.FloorSign)
}

internal val CeladonCity_DepartmentStore_RoofScripts: Map<String, Script> =
    mapOf(
        "CeladonCity_DepartmentStore_Roof_EventScript_CooltrainerM" to
            CeladonCity_DepartmentStore_Roof_EventScript_CooltrainerM,
        "CeladonCity_DepartmentStore_Roof_EventScript_FloorSign" to
            CeladonCity_DepartmentStore_Roof_EventScript_FloorSign,
    )
