package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.OneIsland_PokemonCenter_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's Pokemon Center nurse: heal the party. */
internal object OneIsland_PokemonCenter_1F_EventScript_Nurse : Script {
  override suspend fun run(ctx: ScriptContext) {
    healAtKantoPokemonCenter(ctx)
  }
}

internal object OneIsland_PokemonCenter_1F_EventScript_LittleBoy : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(OneIsland_PokemonCenter_1F.CameFromPalletDontKnowIt)
}

internal object OneIsland_PokemonCenter_1F_EventScript_Hiker : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(OneIsland_PokemonCenter_1F.EnormousVolcanoOnIsland)
}

internal val OneIsland_PokemonCenter_1FScripts: Map<String, Script> =
    mapOf(
        "OneIsland_PokemonCenter_1F_EventScript_Nurse" to
            OneIsland_PokemonCenter_1F_EventScript_Nurse,
        "OneIsland_PokemonCenter_1F_EventScript_LittleBoy" to
            OneIsland_PokemonCenter_1F_EventScript_LittleBoy,
        "OneIsland_PokemonCenter_1F_EventScript_Hiker" to
            OneIsland_PokemonCenter_1F_EventScript_Hiker,
    )
