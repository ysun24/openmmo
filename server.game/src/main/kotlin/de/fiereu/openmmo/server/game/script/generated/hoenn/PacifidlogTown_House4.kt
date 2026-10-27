package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.PacifidlogTown_House4
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object PacifidlogTown_House4_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(PacifidlogTown_House4.PeopleSawHighFlyingPokemon)
}

internal object PacifidlogTown_House4_EventScript_LittleGirl : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(PacifidlogTown_House4.SkyPokemon)
}

internal val PacifidlogTown_House4Scripts: Map<String, Script> =
    mapOf(
        "PacifidlogTown_House4_EventScript_Woman" to PacifidlogTown_House4_EventScript_Woman,
        "PacifidlogTown_House4_EventScript_LittleGirl" to
            PacifidlogTown_House4_EventScript_LittleGirl,
    )
