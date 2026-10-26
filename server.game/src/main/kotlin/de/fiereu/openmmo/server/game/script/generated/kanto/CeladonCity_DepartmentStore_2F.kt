package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.CeladonCity_DepartmentStore_2F
import de.fiereu.openmmo.dialog.generated.kanto.Misc
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object CeladonCity_DepartmentStore_2F_EventScript_Lass : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(CeladonCity_DepartmentStore_2F.BuyReviveForLongOutings)
}

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object CeladonCity_DepartmentStore_2F_EventScript_ClerkItems : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.Text_MayIHelpYou)
    ctx.pokemart(
        Items.GREAT_BALL,
        Items.SUPER_POTION,
        Items.REVIVE,
        Items.ANTIDOTE,
        Items.PARLYZ_HEAL,
        Items.AWAKENING,
        Items.BURN_HEAL,
        Items.ICE_HEAL,
        Items.SUPER_REPEL)
  }
}

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object CeladonCity_DepartmentStore_2F_EventScript_ClerkTMs : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.Text_MayIHelpYou)
    ctx.pokemart(Items.TM05, Items.TM15, Items.TM28, Items.TM31, Items.TM43, Items.TM45)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lock
 * faceplayer
 * famechecker FAMECHECKER_LANCE, 3
 * msgbox CeladonCity_DepartmentStore_2F_Text_LanceComesToBuyCapes
 * release
 * end
 * ```
 */
internal object CeladonCity_DepartmentStore_2F_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) =
      TODO("port CeladonCity_DepartmentStore_2F_EventScript_Woman")
}

internal object CeladonCity_DepartmentStore_2F_EventScript_FloorSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(CeladonCity_DepartmentStore_2F.FloorSign)
}

internal val CeladonCity_DepartmentStore_2FScripts: Map<String, Script> =
    mapOf(
        "CeladonCity_DepartmentStore_2F_EventScript_Lass" to
            CeladonCity_DepartmentStore_2F_EventScript_Lass,
        "CeladonCity_DepartmentStore_2F_EventScript_ClerkItems" to
            CeladonCity_DepartmentStore_2F_EventScript_ClerkItems,
        "CeladonCity_DepartmentStore_2F_EventScript_ClerkTMs" to
            CeladonCity_DepartmentStore_2F_EventScript_ClerkTMs,
        "CeladonCity_DepartmentStore_2F_EventScript_Woman" to
            CeladonCity_DepartmentStore_2F_EventScript_Woman,
        "CeladonCity_DepartmentStore_2F_EventScript_FloorSign" to
            CeladonCity_DepartmentStore_2F_EventScript_FloorSign,
    )
