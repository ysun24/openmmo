package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.FuchsiaCity_House1
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object FuchsiaCity_House1_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(FuchsiaCity_House1.WardenIsOldHasFalseTeeth)
}

internal object FuchsiaCity_House1_EventScript_LittleBoy : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(FuchsiaCity_House1.BillFilesHisOwnMonData)
}

internal val FuchsiaCity_House1Scripts: Map<String, Script> =
    mapOf(
        "FuchsiaCity_House1_EventScript_Woman" to FuchsiaCity_House1_EventScript_Woman,
        "FuchsiaCity_House1_EventScript_LittleBoy" to FuchsiaCity_House1_EventScript_LittleBoy,
    )
