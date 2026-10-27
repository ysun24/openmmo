package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.MossdeepCity_House2
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object MossdeepCity_House2_EventScript_Man : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(MossdeepCity_House2.SisterMailsBoyfriendInFortree)
}

internal object MossdeepCity_House2_EventScript_Twin : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(MossdeepCity_House2.PokemonCarriesMailBackAndForth)
}

internal val MossdeepCity_House2Scripts: Map<String, Script> =
    mapOf(
        "MossdeepCity_House2_EventScript_Man" to MossdeepCity_House2_EventScript_Man,
        "MossdeepCity_House2_EventScript_Twin" to MossdeepCity_House2_EventScript_Twin,
    )
