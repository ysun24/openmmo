package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.SlateportCity_PokemonFanClub
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SlateportCity_PokemonFanClub_EventScript_Man : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(SlateportCity_PokemonFanClub.PokemonDontLikeFainting)
}

internal object SlateportCity_PokemonFanClub_EventScript_Twin : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(SlateportCity_PokemonFanClub.MonEnjoyedProtein)
}

internal val SlateportCity_PokemonFanClubScripts: Map<String, Script> =
    mapOf(
        "SlateportCity_PokemonFanClub_EventScript_Man" to
            SlateportCity_PokemonFanClub_EventScript_Man,
        "SlateportCity_PokemonFanClub_EventScript_Twin" to
            SlateportCity_PokemonFanClub_EventScript_Twin,
    )
