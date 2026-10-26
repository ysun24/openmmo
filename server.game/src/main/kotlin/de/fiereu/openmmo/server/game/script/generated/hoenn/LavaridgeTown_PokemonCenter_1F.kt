package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.LavaridgeTown_PokemonCenter_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's Pokemon Center nurse: heal the party. */
internal object LavaridgeTown_PokemonCenter_1F_EventScript_Nurse : Script {
  override suspend fun run(ctx: ScriptContext) {
    healAtPokemonCenter(ctx)
  }
}

internal object LavaridgeTown_PokemonCenter_1F_EventScript_Youngster : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LavaridgeTown_PokemonCenter_1F.HotSpringCanInvigorate)
}

internal object LavaridgeTown_PokemonCenter_1F_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LavaridgeTown_PokemonCenter_1F.TrainersPokemonSpendTimeTogether)
}

internal object LavaridgeTown_PokemonCenter_1F_EventScript_Gentleman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LavaridgeTown_PokemonCenter_1F.TrainersShouldRestToo)
}

internal val LavaridgeTown_PokemonCenter_1FScripts: Map<String, Script> =
    mapOf(
        "LavaridgeTown_PokemonCenter_1F_EventScript_Nurse" to
            LavaridgeTown_PokemonCenter_1F_EventScript_Nurse,
        "LavaridgeTown_PokemonCenter_1F_EventScript_Youngster" to
            LavaridgeTown_PokemonCenter_1F_EventScript_Youngster,
        "LavaridgeTown_PokemonCenter_1F_EventScript_Woman" to
            LavaridgeTown_PokemonCenter_1F_EventScript_Woman,
        "LavaridgeTown_PokemonCenter_1F_EventScript_Gentleman" to
            LavaridgeTown_PokemonCenter_1F_EventScript_Gentleman,
    )
