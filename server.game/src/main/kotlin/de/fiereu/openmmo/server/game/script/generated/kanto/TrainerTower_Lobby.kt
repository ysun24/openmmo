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

internal object TrainerTower_Lobby_EventScript_CooltrainerF : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(TrainerTower_Lobby.WonderWhatKindsOfTrainers)
}

internal object TrainerTower_Lobby_EventScript_BaldingMan : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(TrainerTower_Lobby.StairsTougherThanAnyBattle)
}

internal val TrainerTower_LobbyScripts: Map<String, Script> =
    mapOf(
        "TrainerTower_Lobby_EventScript_Nurse" to TrainerTower_Lobby_EventScript_Nurse,
        "TrainerTower_Lobby_EventScript_MartClerk" to TrainerTower_Lobby_EventScript_MartClerk,
        "TrainerTower_Lobby_EventScript_CooltrainerF" to
            TrainerTower_Lobby_EventScript_CooltrainerF,
        "TrainerTower_Lobby_EventScript_BaldingMan" to TrainerTower_Lobby_EventScript_BaldingMan,
    )
