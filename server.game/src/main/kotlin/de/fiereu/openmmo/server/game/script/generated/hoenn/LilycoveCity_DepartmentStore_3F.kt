package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.LilycoveCity_DepartmentStore_3F
import de.fiereu.openmmo.dialog.generated.hoenn.Misc
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object LilycoveCity_DepartmentStore_3F_EventScript_TriathleteM : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_DepartmentStore_3F.ItemsBestForTougheningPokemon)
}

internal object LilycoveCity_DepartmentStore_3F_EventScript_PokefanM : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_DepartmentStore_3F.WantMoreEndurance)
}

internal object LilycoveCity_DepartmentStore_3F_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_DepartmentStore_3F.GaveCarbosToSpeedUpMon)
}

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object LilycoveCity_DepartmentStore_3F_EventScript_ClerkLeft : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.gText_HowMayIServeYou)
    ctx.pokemart(Items.PROTEIN, Items.CALCIUM, Items.IRON, Items.ZINC, Items.CARBOS, Items.HP_UP)
  }
}

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object LilycoveCity_DepartmentStore_3F_EventScript_ClerkRight : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.gText_HowMayIServeYou)
    ctx.pokemart(
        Items.X_SPEED,
        Items.X_SPECIAL,
        Items.X_ATTACK,
        Items.X_DEFEND,
        Items.DIRE_HIT,
        Items.GUARD_SPEC,
        Items.X_ACCURACY)
  }
}

internal val LilycoveCity_DepartmentStore_3FScripts: Map<String, Script> =
    mapOf(
        "LilycoveCity_DepartmentStore_3F_EventScript_TriathleteM" to
            LilycoveCity_DepartmentStore_3F_EventScript_TriathleteM,
        "LilycoveCity_DepartmentStore_3F_EventScript_PokefanM" to
            LilycoveCity_DepartmentStore_3F_EventScript_PokefanM,
        "LilycoveCity_DepartmentStore_3F_EventScript_Woman" to
            LilycoveCity_DepartmentStore_3F_EventScript_Woman,
        "LilycoveCity_DepartmentStore_3F_EventScript_ClerkLeft" to
            LilycoveCity_DepartmentStore_3F_EventScript_ClerkLeft,
        "LilycoveCity_DepartmentStore_3F_EventScript_ClerkRight" to
            LilycoveCity_DepartmentStore_3F_EventScript_ClerkRight,
    )
