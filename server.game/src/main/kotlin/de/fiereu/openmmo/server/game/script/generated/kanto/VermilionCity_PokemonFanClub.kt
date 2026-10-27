package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.VermilionCity_PokemonFanClub
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object VermilionCity_PokemonFanClub_EventScript_RulesSign1 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(VermilionCity_PokemonFanClub.ListenPolitelyToOtherTrainers)
}

internal object VermilionCity_PokemonFanClub_EventScript_RulesSign2 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(VermilionCity_PokemonFanClub.SomeoneBragsBragBack)
}

internal val VermilionCity_PokemonFanClubScripts: Map<String, Script> =
    mapOf(
        "VermilionCity_PokemonFanClub_EventScript_RulesSign1" to
            VermilionCity_PokemonFanClub_EventScript_RulesSign1,
        "VermilionCity_PokemonFanClub_EventScript_RulesSign2" to
            VermilionCity_PokemonFanClub_EventScript_RulesSign2,
    )
