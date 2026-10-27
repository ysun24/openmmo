package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.FourIsland
import de.fiereu.openmmo.server.game.progression.DaycareScripts
import de.fiereu.openmmo.server.game.progression.KANTO_DAYCARE
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/**
 * Not ported yet. Decomp body:
 * ```
 * goto_if_questlog EventScript_ReleaseEnd
 * special QuestLog_CutRecording
 * lock
 * faceplayer
 * special GetDaycareMonNicknames
 * specialvar VAR_RESULT, GetDaycareState
 * goto_if_eq VAR_RESULT, DAYCARE_EGG_WAITING, FourIsland_EventScript_DaycareEggWaiting
 * goto_if_eq VAR_RESULT, DAYCARE_ONE_MON, FourIsland_EventScript_CheckOnOneMon
 * goto_if_eq VAR_RESULT, DAYCARE_TWO_MONS, FourIsland_EventScript_CheckOnTwoMons
 * msgbox DayCare_Text_ImDaycareManSpeakToMyWife
 * release
 * end
 * ```
 */
internal object FourIsland_EventScript_DaycareMan : Script {
  override suspend fun run(ctx: ScriptContext) = DaycareScripts.man(ctx, KANTO_DAYCARE)
}

internal object FourIsland_EventScript_FatMan : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(FourIsland.MadeSlushieUsingIcefallCaveIce)
}

internal object FourIsland_EventScript_IslandSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(FourIsland.IslandSign)
}

internal object FourIsland_EventScript_LoreleisHouseSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(FourIsland.LoreleisHouse)
}

internal val FourIslandScripts: Map<String, Script> =
    mapOf(
        "FourIsland_EventScript_DaycareMan" to FourIsland_EventScript_DaycareMan,
        "FourIsland_EventScript_FatMan" to FourIsland_EventScript_FatMan,
        "FourIsland_EventScript_IslandSign" to FourIsland_EventScript_IslandSign,
        "FourIsland_EventScript_LoreleisHouseSign" to FourIsland_EventScript_LoreleisHouseSign,
    )
