package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.MauvilleCity_PokemonCenter_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's Pokemon Center nurse: heal the party. */
internal object MauvilleCity_PokemonCenter_1F_EventScript_Nurse : Script {
  override suspend fun run(ctx: ScriptContext) {
    healAtPokemonCenter(ctx)
  }
}

internal object MauvilleCity_PokemonCenter_1F_EventScript_Woman1 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(MauvilleCity_PokemonCenter_1F.ManOverThereSaysWeirdThings)
}

internal object MauvilleCity_PokemonCenter_1F_EventScript_Woman2 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(MauvilleCity_PokemonCenter_1F.MyDataUpdatedFromRecordCorner)
}

internal object MauvilleCity_PokemonCenter_1F_EventScript_Youngster : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(MauvilleCity_PokemonCenter_1F.RecordCornerSoundsFun)
}

internal val MauvilleCity_PokemonCenter_1FScripts: Map<String, Script> =
    mapOf(
        "MauvilleCity_PokemonCenter_1F_EventScript_Nurse" to
            MauvilleCity_PokemonCenter_1F_EventScript_Nurse,
        "MauvilleCity_PokemonCenter_1F_EventScript_Woman1" to
            MauvilleCity_PokemonCenter_1F_EventScript_Woman1,
        "MauvilleCity_PokemonCenter_1F_EventScript_Woman2" to
            MauvilleCity_PokemonCenter_1F_EventScript_Woman2,
        "MauvilleCity_PokemonCenter_1F_EventScript_Youngster" to
            MauvilleCity_PokemonCenter_1F_EventScript_Youngster,
    )
