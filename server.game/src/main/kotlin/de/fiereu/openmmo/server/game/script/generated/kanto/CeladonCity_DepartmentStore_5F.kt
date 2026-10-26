package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.CeladonCity_DepartmentStore_5F
import de.fiereu.openmmo.dialog.generated.kanto.Misc
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object CeladonCity_DepartmentStore_5F_EventScript_Gentleman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(CeladonCity_DepartmentStore_5F.ExplainStatEnhancers)
}

internal object CeladonCity_DepartmentStore_5F_EventScript_Sailor : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(CeladonCity_DepartmentStore_5F.HereForStatEnhancers)
}

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object CeladonCity_DepartmentStore_5F_EventScript_ClerkXItems : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.Text_MayIHelpYou)
    ctx.pokemart(
        Items.X_ATTACK,
        Items.X_DEFEND,
        Items.X_SPEED,
        Items.X_SPECIAL,
        Items.X_ACCURACY,
        Items.GUARD_SPEC,
        Items.DIRE_HIT)
  }
}

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object CeladonCity_DepartmentStore_5F_EventScript_ClerkVitamins : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.Text_MayIHelpYou)
    ctx.pokemart(Items.HP_UP, Items.PROTEIN, Items.IRON, Items.CALCIUM, Items.ZINC, Items.CARBOS)
  }
}

internal object CeladonCity_DepartmentStore_5F_EventScript_FloorSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(CeladonCity_DepartmentStore_5F.Drugstore)
}

internal val CeladonCity_DepartmentStore_5FScripts: Map<String, Script> =
    mapOf(
        "CeladonCity_DepartmentStore_5F_EventScript_Gentleman" to
            CeladonCity_DepartmentStore_5F_EventScript_Gentleman,
        "CeladonCity_DepartmentStore_5F_EventScript_Sailor" to
            CeladonCity_DepartmentStore_5F_EventScript_Sailor,
        "CeladonCity_DepartmentStore_5F_EventScript_ClerkXItems" to
            CeladonCity_DepartmentStore_5F_EventScript_ClerkXItems,
        "CeladonCity_DepartmentStore_5F_EventScript_ClerkVitamins" to
            CeladonCity_DepartmentStore_5F_EventScript_ClerkVitamins,
        "CeladonCity_DepartmentStore_5F_EventScript_FloorSign" to
            CeladonCity_DepartmentStore_5F_EventScript_FloorSign,
    )
