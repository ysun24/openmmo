package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Route121_SafariZoneEntrance
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route121_SafariZoneEntrance_EventScript_WelcomeAttendant : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(Route121_SafariZoneEntrance.WelcomeToSafariZone)
}

internal object Route121_SafariZoneEntrance_EventScript_TrainerTipSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route121_SafariZoneEntrance.TrainerTip)
}

internal val Route121_SafariZoneEntranceScripts: Map<String, Script> =
    mapOf(
        "Route121_SafariZoneEntrance_EventScript_WelcomeAttendant" to
            Route121_SafariZoneEntrance_EventScript_WelcomeAttendant,
        "Route121_SafariZoneEntrance_EventScript_TrainerTipSign" to
            Route121_SafariZoneEntrance_EventScript_TrainerTipSign,
    )
