package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.CeruleanCity_PokemonCenter_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's Pokemon Center nurse: heal the party. */
internal object CeruleanCity_PokemonCenter_1F_EventScript_Nurse : Script {
  override suspend fun run(ctx: ScriptContext) {
    healAtKantoPokemonCenter(ctx)
  }
}

internal object CeruleanCity_PokemonCenter_1F_EventScript_Gentleman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(CeruleanCity_PokemonCenter_1F.EveryoneCallsBillPokemaniac)
}

internal object CeruleanCity_PokemonCenter_1F_EventScript_Rocker : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(CeruleanCity_PokemonCenter_1F.BillDoesWhateverForRareMons)
}

internal object CeruleanCity_PokemonCenter_1F_EventScript_Lass : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(CeruleanCity_PokemonCenter_1F.TryTradingUpstairs)
}

internal val CeruleanCity_PokemonCenter_1FScripts: Map<String, Script> =
    mapOf(
        "CeruleanCity_PokemonCenter_1F_EventScript_Nurse" to
            CeruleanCity_PokemonCenter_1F_EventScript_Nurse,
        "CeruleanCity_PokemonCenter_1F_EventScript_Gentleman" to
            CeruleanCity_PokemonCenter_1F_EventScript_Gentleman,
        "CeruleanCity_PokemonCenter_1F_EventScript_Rocker" to
            CeruleanCity_PokemonCenter_1F_EventScript_Rocker,
        "CeruleanCity_PokemonCenter_1F_EventScript_Lass" to
            CeruleanCity_PokemonCenter_1F_EventScript_Lass,
    )
