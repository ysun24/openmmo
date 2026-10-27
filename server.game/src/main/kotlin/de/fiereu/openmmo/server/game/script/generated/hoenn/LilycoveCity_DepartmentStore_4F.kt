package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.LilycoveCity_DepartmentStore_4F
import de.fiereu.openmmo.dialog.generated.hoenn.Misc
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object LilycoveCity_DepartmentStore_4F_EventScript_Gentleman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_DepartmentStore_4F.AttackOrDefenseTM)
}

internal object LilycoveCity_DepartmentStore_4F_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_DepartmentStore_4F.FiftyDifferentTMs)
}

internal object LilycoveCity_DepartmentStore_4F_EventScript_Youngster : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_DepartmentStore_4F.PokemonOnlyHaveFourMoves)
}

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object LilycoveCity_DepartmentStore_4F_EventScript_ClerkLeft : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.gText_HowMayIServeYou)
    ctx.pokemart(Items.TM38, Items.TM25, Items.TM14, Items.TM15)
  }
}

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object LilycoveCity_DepartmentStore_4F_EventScript_ClerkRight : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.gText_HowMayIServeYou)
    ctx.pokemart(Items.TM17, Items.TM20, Items.TM33, Items.TM16)
  }
}

internal val LilycoveCity_DepartmentStore_4FScripts: Map<String, Script> =
    mapOf(
        "LilycoveCity_DepartmentStore_4F_EventScript_Gentleman" to
            LilycoveCity_DepartmentStore_4F_EventScript_Gentleman,
        "LilycoveCity_DepartmentStore_4F_EventScript_Woman" to
            LilycoveCity_DepartmentStore_4F_EventScript_Woman,
        "LilycoveCity_DepartmentStore_4F_EventScript_Youngster" to
            LilycoveCity_DepartmentStore_4F_EventScript_Youngster,
        "LilycoveCity_DepartmentStore_4F_EventScript_ClerkLeft" to
            LilycoveCity_DepartmentStore_4F_EventScript_ClerkLeft,
        "LilycoveCity_DepartmentStore_4F_EventScript_ClerkRight" to
            LilycoveCity_DepartmentStore_4F_EventScript_ClerkRight,
    )
