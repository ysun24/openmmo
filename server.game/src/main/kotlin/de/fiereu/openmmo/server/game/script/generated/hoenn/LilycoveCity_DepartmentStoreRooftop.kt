package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.LilycoveCity_DepartmentStoreRooftop
import de.fiereu.openmmo.dialog.generated.hoenn.Misc
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object LilycoveCity_DepartmentStoreRooftop_EventScript_ThirstyMan : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_DepartmentStoreRooftop.BoneDryThirsty)
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lock
 * faceplayer
 * message gText_HowMayIServeYou
 * waitmessage
 * pokemartdecoration LilycoveCity_DepartmentStoreRooftop_PokemartDecor_ClearOutSale
 * msgbox gText_PleaseComeAgain, MSGBOX_DEFAULT
 * release
 * end
 * ```
 */
internal object LilycoveCity_DepartmentStoreRooftop_EventScript_SaleWoman : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.gText_HowMayIServeYou)
    ctx.decorationShop("LilycoveCity_DepartmentStoreRooftop_PokemartDecor_ClearOutSale")
  }
}

internal val LilycoveCity_DepartmentStoreRooftopScripts: Map<String, Script> =
    mapOf(
        "LilycoveCity_DepartmentStoreRooftop_EventScript_ThirstyMan" to
            LilycoveCity_DepartmentStoreRooftop_EventScript_ThirstyMan,
        "LilycoveCity_DepartmentStoreRooftop_EventScript_SaleWoman" to
            LilycoveCity_DepartmentStoreRooftop_EventScript_SaleWoman,
    )
