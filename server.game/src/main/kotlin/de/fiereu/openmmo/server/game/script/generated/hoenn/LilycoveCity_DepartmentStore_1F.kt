package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.LilycoveCity_DepartmentStore_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object LilycoveCity_DepartmentStore_1F_EventScript_Greeter : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_DepartmentStore_1F.WelcomeToDeptStore)
}

internal object LilycoveCity_DepartmentStore_1F_EventScript_PokefanF : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_DepartmentStore_1F.IBuyAllSortsOfThings)
}

internal object LilycoveCity_DepartmentStore_1F_EventScript_LittleGirl : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_DepartmentStore_1F.MomBuyingMeFurniture)
}

internal object LilycoveCity_DepartmentStore_1F_EventScript_PokefanM : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_DepartmentStore_1F.BuyingSomethingForAzumarill)
}

internal object LilycoveCity_DepartmentStore_1F_EventScript_FloorNamesSign : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(LilycoveCity_DepartmentStore_1F.FloorNamesSign)
}

internal val LilycoveCity_DepartmentStore_1FScripts: Map<String, Script> =
    mapOf(
        "LilycoveCity_DepartmentStore_1F_EventScript_Greeter" to
            LilycoveCity_DepartmentStore_1F_EventScript_Greeter,
        "LilycoveCity_DepartmentStore_1F_EventScript_PokefanF" to
            LilycoveCity_DepartmentStore_1F_EventScript_PokefanF,
        "LilycoveCity_DepartmentStore_1F_EventScript_LittleGirl" to
            LilycoveCity_DepartmentStore_1F_EventScript_LittleGirl,
        "LilycoveCity_DepartmentStore_1F_EventScript_PokefanM" to
            LilycoveCity_DepartmentStore_1F_EventScript_PokefanM,
        "LilycoveCity_DepartmentStore_1F_EventScript_FloorNamesSign" to
            LilycoveCity_DepartmentStore_1F_EventScript_FloorNamesSign,
    )
