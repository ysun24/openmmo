package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.CeladonCity_GameCorner
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object CeladonCity_GameCorner_EventScript_InfoClerk : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(CeladonCity_GameCorner.CanExchangeCoinsNextDoor)
}

internal object CeladonCity_GameCorner_EventScript_UnusableSlotMachine1 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(CeladonCity_GameCorner.OutOfOrder)
}

internal object CeladonCity_GameCorner_EventScript_UnusableSlotMachine2 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(CeladonCity_GameCorner.OutToLunch)
}

internal object CeladonCity_GameCorner_EventScript_UnusableSlotMachine3 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(CeladonCity_GameCorner.SomeonesKeys)
}

internal val CeladonCity_GameCornerScripts: Map<String, Script> =
    mapOf(
        "CeladonCity_GameCorner_EventScript_InfoClerk" to
            CeladonCity_GameCorner_EventScript_InfoClerk,
        "CeladonCity_GameCorner_EventScript_UnusableSlotMachine1" to
            CeladonCity_GameCorner_EventScript_UnusableSlotMachine1,
        "CeladonCity_GameCorner_EventScript_UnusableSlotMachine2" to
            CeladonCity_GameCorner_EventScript_UnusableSlotMachine2,
        "CeladonCity_GameCorner_EventScript_UnusableSlotMachine3" to
            CeladonCity_GameCorner_EventScript_UnusableSlotMachine3,
    )
