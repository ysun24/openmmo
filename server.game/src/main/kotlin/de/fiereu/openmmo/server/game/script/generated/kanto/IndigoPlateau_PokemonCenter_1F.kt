package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.IndigoPlateau_PokemonCenter_1F
import de.fiereu.openmmo.dialog.generated.kanto.Misc
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object IndigoPlateau_PokemonCenter_1F_EventScript_Clerk : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.Text_MayIHelpYou)
    ctx.pokemart(
        Items.ULTRA_BALL,
        Items.GREAT_BALL,
        Items.FULL_RESTORE,
        Items.MAX_POTION,
        Items.REVIVE,
        Items.FULL_HEAL,
        Items.MAX_REPEL)
  }
}

/** The decomp's Pokemon Center nurse: heal the party. */
internal object IndigoPlateau_PokemonCenter_1F_EventScript_Nurse : Script {
  override suspend fun run(ctx: ScriptContext) {
    healAtKantoPokemonCenter(ctx)
  }
}

internal object IndigoPlateau_PokemonCenter_1F_EventScript_GymGuy : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(IndigoPlateau_PokemonCenter_1F.GymGuyAdvice)
}

internal val IndigoPlateau_PokemonCenter_1FScripts: Map<String, Script> =
    mapOf(
        "IndigoPlateau_PokemonCenter_1F_EventScript_Clerk" to
            IndigoPlateau_PokemonCenter_1F_EventScript_Clerk,
        "IndigoPlateau_PokemonCenter_1F_EventScript_Nurse" to
            IndigoPlateau_PokemonCenter_1F_EventScript_Nurse,
        "IndigoPlateau_PokemonCenter_1F_EventScript_GymGuy" to
            IndigoPlateau_PokemonCenter_1F_EventScript_GymGuy,
    )
