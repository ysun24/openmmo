package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.FiveIsland_PokemonCenter_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's Pokemon Center nurse: heal the party. */
internal object FiveIsland_PokemonCenter_1F_EventScript_Nurse : Script {
  override suspend fun run(ctx: ScriptContext) {
    healAtKantoPokemonCenter(ctx)
  }
}

internal object FiveIsland_PokemonCenter_1F_EventScript_CooltrainerF : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(FiveIsland_PokemonCenter_1F.YoureOnQuestToCatchEveryMon)
}

internal object FiveIsland_PokemonCenter_1F_EventScript_Chef : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(FiveIsland_PokemonCenter_1F.OnMyWayToPrepareMeal)
}

internal val FiveIsland_PokemonCenter_1FScripts: Map<String, Script> =
    mapOf(
        "FiveIsland_PokemonCenter_1F_EventScript_Nurse" to
            FiveIsland_PokemonCenter_1F_EventScript_Nurse,
        "FiveIsland_PokemonCenter_1F_EventScript_CooltrainerF" to
            FiveIsland_PokemonCenter_1F_EventScript_CooltrainerF,
        "FiveIsland_PokemonCenter_1F_EventScript_Chef" to
            FiveIsland_PokemonCenter_1F_EventScript_Chef,
    )
