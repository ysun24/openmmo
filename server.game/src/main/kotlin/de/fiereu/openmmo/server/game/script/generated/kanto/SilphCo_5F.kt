package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.SilphCo_5F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SilphCo_5F_EventScript_PokemonReport3 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SilphCo_5F.SomeMonsEvolveWhenTraded)
}

internal object SilphCo_5F_EventScript_PokemonReport2 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SilphCo_5F.Over350TechniquesConfirmed)
}

internal object SilphCo_5F_EventScript_PokemonReport1 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SilphCo_5F.PorygonFirstVRMon)
}

internal object SilphCo_5F_EventScript_FloorSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SilphCo_5F.FloorSign)
}

internal val SilphCo_5FScripts: Map<String, Script> =
    mapOf(
        "SilphCo_5F_EventScript_PokemonReport3" to SilphCo_5F_EventScript_PokemonReport3,
        "SilphCo_5F_EventScript_PokemonReport2" to SilphCo_5F_EventScript_PokemonReport2,
        "SilphCo_5F_EventScript_PokemonReport1" to SilphCo_5F_EventScript_PokemonReport1,
        "SilphCo_5F_EventScript_FloorSign" to SilphCo_5F_EventScript_FloorSign,
    )
