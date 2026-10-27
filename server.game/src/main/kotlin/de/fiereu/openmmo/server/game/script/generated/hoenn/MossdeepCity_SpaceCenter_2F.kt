package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.MossdeepCity_SpaceCenter_2F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object MossdeepCity_SpaceCenter_2F_EventScript_Grunt6 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(MossdeepCity_SpaceCenter_2F.Grunt6PostBattle)
}

internal object MossdeepCity_SpaceCenter_2F_EventScript_Grunt5 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(MossdeepCity_SpaceCenter_2F.Grunt5PostBattle)
}

internal object MossdeepCity_SpaceCenter_2F_EventScript_Grunt7 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(MossdeepCity_SpaceCenter_2F.Grunt7PostBattle)
}

internal object MossdeepCity_SpaceCenter_2F_EventScript_Maxie : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(MossdeepCity_SpaceCenter_2F.MaxieDontInterfere)
}

internal val MossdeepCity_SpaceCenter_2FScripts: Map<String, Script> =
    mapOf(
        "MossdeepCity_SpaceCenter_2F_EventScript_Grunt6" to
            MossdeepCity_SpaceCenter_2F_EventScript_Grunt6,
        "MossdeepCity_SpaceCenter_2F_EventScript_Grunt5" to
            MossdeepCity_SpaceCenter_2F_EventScript_Grunt5,
        "MossdeepCity_SpaceCenter_2F_EventScript_Grunt7" to
            MossdeepCity_SpaceCenter_2F_EventScript_Grunt7,
        "MossdeepCity_SpaceCenter_2F_EventScript_Maxie" to
            MossdeepCity_SpaceCenter_2F_EventScript_Maxie,
    )
