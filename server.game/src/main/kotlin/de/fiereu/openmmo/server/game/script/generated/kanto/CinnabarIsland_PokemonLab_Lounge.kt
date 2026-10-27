package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.CinnabarIsland_PokemonLab_Lounge
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object CinnabarIsland_PokemonLab_Lounge_EventScript_Scientist : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(CinnabarIsland_PokemonLab_Lounge.FoundFossilInMtMoon)
}

internal val CinnabarIsland_PokemonLab_LoungeScripts: Map<String, Script> =
    mapOf(
        "CinnabarIsland_PokemonLab_Lounge_EventScript_Scientist" to
            CinnabarIsland_PokemonLab_Lounge_EventScript_Scientist,
    )
