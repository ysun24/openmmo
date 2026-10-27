package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Misc
import de.fiereu.openmmo.dialog.generated.kanto.TrainerTower_Lobby
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The decomp's Pokemon Center nurse: heal the party. */
internal object TrainerTower_Lobby_EventScript_Nurse : Script {
  override suspend fun run(ctx: ScriptContext) {
    healAtKantoPokemonCenter(ctx)
  }
}

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object TrainerTower_Lobby_EventScript_MartClerk : Script {
  override suspend fun run(ctx: ScriptContext) {
    ctx.say(Misc.Text_MayIHelpYou)
    ctx.pokemart(
        Items.ULTRA_BALL,
        Items.GREAT_BALL,
        Items.FULL_RESTORE,
        Items.MAX_POTION,
        Items.HYPER_POTION,
        Items.REVIVE,
        Items.FULL_HEAL,
        Items.ESCAPE_ROPE,
        Items.MAX_REPEL)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lock
 * faceplayer
 * ttower_getbeatchallenge
 * goto_if_eq VAR_RESULT, TRUE, TrainerTower_Lobby_EventScript_ThanksForCompeting
 * msgbox TrainerTower_Lobby_Text_GiveItYourBest
 * goto TrainerTower_Lobby_EventScript_ReceptionistEnd
 * ```
 */
internal object TrainerTower_Lobby_EventScript_Receptionist : Script {
  override suspend fun run(ctx: ScriptContext) =
      TODO("port TrainerTower_Lobby_EventScript_Receptionist")
}

internal object TrainerTower_Lobby_EventScript_CooltrainerF : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(TrainerTower_Lobby.WonderWhatKindsOfTrainers)
}

internal object TrainerTower_Lobby_EventScript_BaldingMan : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(TrainerTower_Lobby.StairsTougherThanAnyBattle)
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lockall
 * fadescreen FADE_TO_BLACK
 * setvar VAR_0x8004, 1
 * special ShowBattleRecords
 * waitstate
 * releaseall
 * end
 * ```
 */
internal object TrainerTower_Lobby_EventScript_ShowRecords : Script {
  override suspend fun run(ctx: ScriptContext) =
      TODO("port TrainerTower_Lobby_EventScript_ShowRecords")
}

internal val TrainerTower_LobbyScripts: Map<String, Script> =
    mapOf(
        "TrainerTower_Lobby_EventScript_Nurse" to TrainerTower_Lobby_EventScript_Nurse,
        "TrainerTower_Lobby_EventScript_MartClerk" to TrainerTower_Lobby_EventScript_MartClerk,
        "TrainerTower_Lobby_EventScript_Receptionist" to
            TrainerTower_Lobby_EventScript_Receptionist,
        "TrainerTower_Lobby_EventScript_CooltrainerF" to
            TrainerTower_Lobby_EventScript_CooltrainerF,
        "TrainerTower_Lobby_EventScript_BaldingMan" to TrainerTower_Lobby_EventScript_BaldingMan,
        "TrainerTower_Lobby_EventScript_ShowRecords" to TrainerTower_Lobby_EventScript_ShowRecords,
    )
