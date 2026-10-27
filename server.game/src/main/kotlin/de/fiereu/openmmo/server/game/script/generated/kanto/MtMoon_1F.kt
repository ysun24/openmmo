package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.MtMoon_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object MtMoon_1F_EventScript_ZubatSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(MtMoon_1F.ZubatIsABloodsucker)
}

internal val MtMoon_1FScripts: Map<String, Script> =
    mapOf(
        "MtMoon_1F_EventScript_ZubatSign" to MtMoon_1F_EventScript_ZubatSign,
    )
