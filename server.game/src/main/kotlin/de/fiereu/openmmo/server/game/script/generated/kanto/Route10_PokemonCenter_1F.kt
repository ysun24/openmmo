package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Route10_PokemonCenter_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's Pokemon Center nurse: heal the party. */
internal object Route10_PokemonCenter_1F_EventScript_Nurse : Script {
  override suspend fun run(ctx: ScriptContext) {
    healAtKantoPokemonCenter(ctx)
  }
}

internal object Route10_PokemonCenter_1F_EventScript_Gentleman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(Route10_PokemonCenter_1F.EveryTypeStrongerThanOthers)
}

internal object Route10_PokemonCenter_1F_EventScript_FatMan : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(Route10_PokemonCenter_1F.NuggetUselessSoldFor5000)
}

internal object Route10_PokemonCenter_1F_EventScript_Youngster : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(Route10_PokemonCenter_1F.HeardGhostsHauntLavender)
}

internal val Route10_PokemonCenter_1FScripts: Map<String, Script> =
    mapOf(
        "Route10_PokemonCenter_1F_EventScript_Nurse" to Route10_PokemonCenter_1F_EventScript_Nurse,
        "Route10_PokemonCenter_1F_EventScript_Gentleman" to
            Route10_PokemonCenter_1F_EventScript_Gentleman,
        "Route10_PokemonCenter_1F_EventScript_FatMan" to
            Route10_PokemonCenter_1F_EventScript_FatMan,
        "Route10_PokemonCenter_1F_EventScript_Youngster" to
            Route10_PokemonCenter_1F_EventScript_Youngster,
    )
