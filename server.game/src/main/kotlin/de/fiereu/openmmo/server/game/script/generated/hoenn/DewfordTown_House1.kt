package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.DewfordTown_House1
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object DewfordTown_House1_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(DewfordTown_House1.LifeGoesSlowlyOnIsland)
}

internal object DewfordTown_House1_EventScript_Man : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(DewfordTown_House1.LotToBeSaidForLivingOnIsland)
}

internal val DewfordTown_House1Scripts: Map<String, Script> =
    mapOf(
        "DewfordTown_House1_EventScript_Woman" to DewfordTown_House1_EventScript_Woman,
        "DewfordTown_House1_EventScript_Man" to DewfordTown_House1_EventScript_Man,
    )
