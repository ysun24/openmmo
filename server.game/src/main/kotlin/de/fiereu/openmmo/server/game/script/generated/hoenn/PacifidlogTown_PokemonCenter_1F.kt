package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.PacifidlogTown_PokemonCenter_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's Pokemon Center nurse: heal the party. */
internal object PacifidlogTown_PokemonCenter_1F_EventScript_Nurse : Script {
  override suspend fun run(ctx: ScriptContext) {
    healAtPokemonCenter(ctx)
  }
}

internal object PacifidlogTown_PokemonCenter_1F_EventScript_OldMan : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(PacifidlogTown_PokemonCenter_1F.AncestorsLivedOnBoats)
}

internal object PacifidlogTown_PokemonCenter_1F_EventScript_Girl : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(PacifidlogTown_PokemonCenter_1F.WhatColorTrainerCard)
}

internal object PacifidlogTown_PokemonCenter_1F_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(PacifidlogTown_PokemonCenter_1F.OnColonyOfCorsola)
}

internal val PacifidlogTown_PokemonCenter_1FScripts: Map<String, Script> =
    mapOf(
        "PacifidlogTown_PokemonCenter_1F_EventScript_Nurse" to
            PacifidlogTown_PokemonCenter_1F_EventScript_Nurse,
        "PacifidlogTown_PokemonCenter_1F_EventScript_OldMan" to
            PacifidlogTown_PokemonCenter_1F_EventScript_OldMan,
        "PacifidlogTown_PokemonCenter_1F_EventScript_Girl" to
            PacifidlogTown_PokemonCenter_1F_EventScript_Girl,
        "PacifidlogTown_PokemonCenter_1F_EventScript_Woman" to
            PacifidlogTown_PokemonCenter_1F_EventScript_Woman,
    )
