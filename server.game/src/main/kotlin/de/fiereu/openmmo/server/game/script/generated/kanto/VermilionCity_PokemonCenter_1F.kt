package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.VermilionCity_PokemonCenter_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's Pokemon Center nurse: heal the party. */
internal object VermilionCity_PokemonCenter_1F_EventScript_Nurse : Script {
  override suspend fun run(ctx: ScriptContext) {
    healAtKantoPokemonCenter(ctx)
  }
}

internal object VermilionCity_PokemonCenter_1F_EventScript_Man : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(VermilionCity_PokemonCenter_1F.PoisonedMonFaintedWhileWalking)
}

internal object VermilionCity_PokemonCenter_1F_EventScript_Hiker : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(VermilionCity_PokemonCenter_1F.TrainerMonsStrongerThanWild)
}

internal object VermilionCity_PokemonCenter_1F_EventScript_Youngster : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(VermilionCity_PokemonCenter_1F.AllMonWeakToSpecificTypes)
}

internal val VermilionCity_PokemonCenter_1FScripts: Map<String, Script> =
    mapOf(
        "VermilionCity_PokemonCenter_1F_EventScript_Nurse" to
            VermilionCity_PokemonCenter_1F_EventScript_Nurse,
        "VermilionCity_PokemonCenter_1F_EventScript_Man" to
            VermilionCity_PokemonCenter_1F_EventScript_Man,
        "VermilionCity_PokemonCenter_1F_EventScript_Hiker" to
            VermilionCity_PokemonCenter_1F_EventScript_Hiker,
        "VermilionCity_PokemonCenter_1F_EventScript_Youngster" to
            VermilionCity_PokemonCenter_1F_EventScript_Youngster,
    )
