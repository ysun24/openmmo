package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.CeladonCity_Condominiums_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object CeladonCity_Condominiums_1F_EventScript_SuiteSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(CeladonCity_Condominiums_1F.ManagersSuite)
}

internal val CeladonCity_Condominiums_1FScripts: Map<String, Script> =
    mapOf(
        "CeladonCity_Condominiums_1F_EventScript_SuiteSign" to
            CeladonCity_Condominiums_1F_EventScript_SuiteSign,
    )
