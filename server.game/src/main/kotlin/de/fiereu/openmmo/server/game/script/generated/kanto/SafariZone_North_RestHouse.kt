package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.SafariZone_East
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SafariZone_North_RestHouse_EventScript_Gentleman : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(SafariZone_East.MyEeveeEvolvedIntoFlareon)
}

internal object SafariZone_North_RestHouse_EventScript_Scientist : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(SafariZone_East.KeepAnyItemFoundOnSafari)
}

internal object SafariZone_North_RestHouse_EventScript_Fisher : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(SafariZone_East.PrizeInDeepestPartOfSafariZone)
}

internal val SafariZone_North_RestHouseScripts: Map<String, Script> =
    mapOf(
        "SafariZone_North_RestHouse_EventScript_Gentleman" to
            SafariZone_North_RestHouse_EventScript_Gentleman,
        "SafariZone_North_RestHouse_EventScript_Scientist" to
            SafariZone_North_RestHouse_EventScript_Scientist,
        "SafariZone_North_RestHouse_EventScript_Fisher" to
            SafariZone_North_RestHouse_EventScript_Fisher,
    )
