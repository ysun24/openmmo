package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.CeladonCity_PokemonCenter_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's Pokemon Center nurse: heal the party. */
internal object CeladonCity_PokemonCenter_1F_EventScript_Nurse : Script {
  override suspend fun run(ctx: ScriptContext) {
    healAtKantoPokemonCenter(ctx)
  }
}

internal object CeladonCity_PokemonCenter_1F_EventScript_Gentleman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(CeladonCity_PokemonCenter_1F.PokeFluteAwakensSleepingMons)
}

internal object CeladonCity_PokemonCenter_1F_EventScript_CooltrainerF : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(CeladonCity_PokemonCenter_1F.RodeHereFromFuchsia)
}

internal object CeladonCity_PokemonCenter_1F_EventScript_Youngster : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(CeladonCity_PokemonCenter_1F.GoToCyclingRoadIfIHadBike)
}

internal val CeladonCity_PokemonCenter_1FScripts: Map<String, Script> =
    mapOf(
        "CeladonCity_PokemonCenter_1F_EventScript_Nurse" to
            CeladonCity_PokemonCenter_1F_EventScript_Nurse,
        "CeladonCity_PokemonCenter_1F_EventScript_Gentleman" to
            CeladonCity_PokemonCenter_1F_EventScript_Gentleman,
        "CeladonCity_PokemonCenter_1F_EventScript_CooltrainerF" to
            CeladonCity_PokemonCenter_1F_EventScript_CooltrainerF,
        "CeladonCity_PokemonCenter_1F_EventScript_Youngster" to
            CeladonCity_PokemonCenter_1F_EventScript_Youngster,
    )
