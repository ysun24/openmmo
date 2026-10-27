package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.BattleFrontier_PokemonCenter_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's Pokemon Center nurse: heal the party. */
internal object BattleFrontier_PokemonCenter_1F_EventScript_Nurse : Script {
  override suspend fun run(ctx: ScriptContext) {
    healAtPokemonCenter(ctx)
  }
}

internal object BattleFrontier_PokemonCenter_1F_EventScript_SchoolKid : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_PokemonCenter_1F.NeverSeenPokemon)
}

internal object BattleFrontier_PokemonCenter_1F_EventScript_Man : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_PokemonCenter_1F.NextStopBattleArena)
}

internal object BattleFrontier_PokemonCenter_1F_EventScript_Picnicker : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(BattleFrontier_PokemonCenter_1F.GoingThroughEveryChallenge)
}

internal val BattleFrontier_PokemonCenter_1FScripts: Map<String, Script> =
    mapOf(
        "BattleFrontier_PokemonCenter_1F_EventScript_Nurse" to
            BattleFrontier_PokemonCenter_1F_EventScript_Nurse,
        "BattleFrontier_PokemonCenter_1F_EventScript_SchoolKid" to
            BattleFrontier_PokemonCenter_1F_EventScript_SchoolKid,
        "BattleFrontier_PokemonCenter_1F_EventScript_Man" to
            BattleFrontier_PokemonCenter_1F_EventScript_Man,
        "BattleFrontier_PokemonCenter_1F_EventScript_Picnicker" to
            BattleFrontier_PokemonCenter_1F_EventScript_Picnicker,
    )
