package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.PewterCity_PokemonCenter_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object PewterCity_PokemonCenter_1F_EventScript_Gentleman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(PewterCity_PokemonCenter_1F.TeamRocketMtMoonImOnPhone)
}

/** The decomp's Pokemon Center nurse: heal the party. */
internal object PewterCity_PokemonCenter_1F_EventScript_Nurse : Script {
  override suspend fun run(ctx: ScriptContext) {
    healAtKantoPokemonCenter(ctx)
  }
}

internal object PewterCity_PokemonCenter_1F_EventScript_Youngster : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(PewterCity_PokemonCenter_1F.WhenJiggylypuffSingsMonsGetDrowsy)
}

internal object PewterCity_PokemonCenter_1F_EventScript_GBAKid1 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(PewterCity_PokemonCenter_1F.TradingMyClefairyForPikachu)
}

internal object PewterCity_PokemonCenter_1F_EventScript_GBAKid2 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(PewterCity_PokemonCenter_1F.TradingPikachuWithKid)
}

internal val PewterCity_PokemonCenter_1FScripts: Map<String, Script> =
    mapOf(
        "PewterCity_PokemonCenter_1F_EventScript_Gentleman" to
            PewterCity_PokemonCenter_1F_EventScript_Gentleman,
        "PewterCity_PokemonCenter_1F_EventScript_Nurse" to
            PewterCity_PokemonCenter_1F_EventScript_Nurse,
        "PewterCity_PokemonCenter_1F_EventScript_Youngster" to
            PewterCity_PokemonCenter_1F_EventScript_Youngster,
        "PewterCity_PokemonCenter_1F_EventScript_GBAKid1" to
            PewterCity_PokemonCenter_1F_EventScript_GBAKid1,
        "PewterCity_PokemonCenter_1F_EventScript_GBAKid2" to
            PewterCity_PokemonCenter_1F_EventScript_GBAKid2,
    )
