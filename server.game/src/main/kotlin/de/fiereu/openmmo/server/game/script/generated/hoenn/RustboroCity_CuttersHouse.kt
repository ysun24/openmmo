package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.RustboroCity_CuttersHouse
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object RustboroCity_CuttersHouse_EventScript_Lass : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(RustboroCity_CuttersHouse.DadHelpedClearLandOfTrees)
}

internal val RustboroCity_CuttersHouseScripts: Map<String, Script> =
    mapOf(
        "RustboroCity_CuttersHouse_EventScript_Lass" to RustboroCity_CuttersHouse_EventScript_Lass,
    )
