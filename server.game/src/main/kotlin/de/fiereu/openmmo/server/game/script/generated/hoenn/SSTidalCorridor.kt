package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.SSTidalCorridor
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SSTidalCorridor_EventScript_Briney : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(SSTidalCorridor.BrineyWelcomeAboard)
}

internal object SSTidalCorridor_EventScript_Cabin1Sign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SSTidalCorridor.Cabin1)
}

internal object SSTidalCorridor_EventScript_Cabin2Sign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SSTidalCorridor.Cabin2)
}

internal object SSTidalCorridor_EventScript_Cabin3Sign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SSTidalCorridor.Cabin3)
}

internal object SSTidalCorridor_EventScript_Cabin4Sign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SSTidalCorridor.Cabin4)
}

internal val SSTidalCorridorScripts: Map<String, Script> =
    mapOf(
        "SSTidalCorridor_EventScript_Briney" to SSTidalCorridor_EventScript_Briney,
        "SSTidalCorridor_EventScript_Cabin1Sign" to SSTidalCorridor_EventScript_Cabin1Sign,
        "SSTidalCorridor_EventScript_Cabin2Sign" to SSTidalCorridor_EventScript_Cabin2Sign,
        "SSTidalCorridor_EventScript_Cabin3Sign" to SSTidalCorridor_EventScript_Cabin3Sign,
        "SSTidalCorridor_EventScript_Cabin4Sign" to SSTidalCorridor_EventScript_Cabin4Sign,
    )
