package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.RustboroCity_Flat1_2F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object RustboroCity_Flat1_2F_EventScript_WaldasMom : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(RustboroCity_Flat1_2F.ComingUpWithMealsIsHard)
}

internal object RustboroCity_Flat1_2F_EventScript_PokeDoll : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(RustboroCity_Flat1_2F.ItsAPokemonPlushDoll)
}

internal val RustboroCity_Flat1_2FScripts: Map<String, Script> =
    mapOf(
        "RustboroCity_Flat1_2F_EventScript_WaldasMom" to
            RustboroCity_Flat1_2F_EventScript_WaldasMom,
        "RustboroCity_Flat1_2F_EventScript_PokeDoll" to RustboroCity_Flat1_2F_EventScript_PokeDoll,
    )
