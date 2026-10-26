package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Misc
import de.fiereu.openmmo.dialog.generated.hoenn.VerdanturfTown_Mart
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object VerdanturfTown_Mart_EventScript_Clerk : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.gText_HowMayIServeYou)
    ctx.pokemart(
        Items.GREAT_BALL,
        Items.NEST_BALL,
        Items.SUPER_POTION,
        Items.ANTIDOTE,
        Items.PARLYZ_HEAL,
        Items.AWAKENING,
        Items.BURN_HEAL,
        Items.ICE_HEAL,
        Items.REPEL,
        Items.X_SPECIAL,
        Items.FLUFFY_TAIL)
  }
}

internal object VerdanturfTown_Mart_EventScript_Boy : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(VerdanturfTown_Mart.XSpecialIsCrucial)
}

internal object VerdanturfTown_Mart_EventScript_ExpertF : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(VerdanturfTown_Mart.NoStrategyGuidesForBattleTent)
}

internal object VerdanturfTown_Mart_EventScript_Lass : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(VerdanturfTown_Mart.NestBallOnWeakenedPokemon)
}

internal val VerdanturfTown_MartScripts: Map<String, Script> =
    mapOf(
        "VerdanturfTown_Mart_EventScript_Clerk" to VerdanturfTown_Mart_EventScript_Clerk,
        "VerdanturfTown_Mart_EventScript_Boy" to VerdanturfTown_Mart_EventScript_Boy,
        "VerdanturfTown_Mart_EventScript_ExpertF" to VerdanturfTown_Mart_EventScript_ExpertF,
        "VerdanturfTown_Mart_EventScript_Lass" to VerdanturfTown_Mart_EventScript_Lass,
    )
