package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.PetalburgCity_Gym
import de.fiereu.openmmo.server.game.script.MovementStep.SET_INVISIBLE
import de.fiereu.openmmo.server.game.script.MovementStep.WALK_DOWN
import de.fiereu.openmmo.server.game.progression.gymTrainer
import de.fiereu.openmmo.server.game.battle.BattleResult
import de.fiereu.openmmo.server.game.progression.badgeAwarded
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext
import de.fiereu.openmmo.story.generated.hoenn.HoennFlags
import de.fiereu.openmmo.story.generated.hoenn.HoennVars

private const val LOCALID_NORMAN = 0
private const val LOCALID_WALLY = 9

internal object PetalburgCity_Gym_OnTransition : Script {
  override suspend fun run(ctx: ScriptContext) {
    val state = ctx.getVar(HoennVars.VAR_PETALBURG_GYM_STATE)
    if (state < 6) ctx.showNpcAt(LOCALID_NORMAN, 4, 107)
    if (state == 1) ctx.showNpcAt(LOCALID_WALLY, 5, 108)
  }
}

internal object PetalburgCity_Gym_EventScript_ReturnFromWallyTutorial : Script {
  override suspend fun run(ctx: ScriptContext) {
    if (ctx.getVar(HoennVars.VAR_PETALBURG_GYM_STATE) != 1) return
    ctx.sayNpc(LOCALID_NORMAN, PetalburgCity_Gym.DadSoDidItWorkOut)
    ctx.sayNpc(LOCALID_WALLY, PetalburgCity_Gym.WallyThankYouBye)
    ctx.moveNpc(LOCALID_WALLY, WALK_DOWN, WALK_DOWN, WALK_DOWN, SET_INVISIBLE)
    ctx.setFlag(HoennFlags.FLAG_HIDE_PETALBURG_CITY_WALLY)
    ctx.setFlag(HoennFlags.FLAG_HIDE_PETALBURG_GYM_WALLY)
    ctx.sayNpc(LOCALID_NORMAN, PetalburgCity_Gym.DadGoCollectBadges)
    ctx.setVar(HoennVars.VAR_PETALBURG_GYM_STATE, 2)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lock
 * faceplayer
 * switch VAR_PETALBURG_GYM_STATE
 * case 2, PetalburgCity_Gym_EventScript_NormanNoBadges
 * case 3, PetalburgCity_Gym_EventScript_NormanOneBadge
 * case 4, PetalburgCity_Gym_EventScript_NormanTwoBadges
 * case 5, PetalburgCity_Gym_EventScript_NormanThreeBadges
 * case 6, PetalburgCity_Gym_EventScript_NormanBattle
 * case 7, PetalburgCity_Gym_EventScript_NormanPostBattle
 * case 8, PetalburgCity_Gym_EventScript_NormanRematch
 * msgbox PetalburgCity_Gym_Text_DadYoureHereWithYourPokemon, MSGBOX_DEFAULT
 * closemessage
 * switch VAR_FACING
 * case DIR_SOUTH, PetalburgCity_Gym_EventScript_BeginWallyTutorialSouth
 * case DIR_NORTH, PetalburgCity_Gym_EventScript_BeginWallyTutorialNorth
 * case DIR_WEST, PetalburgCity_Gym_EventScript_BeginWallyTutorialWest
 * case DIR_EAST, PetalburgCity_Gym_EventScript_BeginWallyTutorialEast
 * end
 * ```
 */
internal object PetalburgCity_Gym_EventScript_Norman : Script {
  override suspend fun run(ctx: ScriptContext) {
    when (ctx.getVar(HoennVars.VAR_PETALBURG_GYM_STATE)) {
      0 -> beginWallyTutorial(ctx)
      1 -> PetalburgCity_Gym_EventScript_ReturnFromWallyTutorial.run(ctx)
      2 -> ctx.say(PetalburgCity_Gym.NormanGoToRustboro)
      3 -> ctx.say(PetalburgCity_Gym.NormanGoToDewford)
      4,
      5 -> ctx.say(PetalburgCity_Gym.YouHaveGottenStronger)
      6 -> normanBattle(ctx)
      else ->
          if (!ctx.isFlagSet(HoennFlags.FLAG_RECEIVED_TM_FACADE)) giveFacade(ctx)
          else ctx.say(PetalburgCity_Gym.DadGoingToKeepTraining)
    }
  }
}

/** PetalburgCity_Gym_EventScript_NormanBattle, without Wally's father's walk-in afterwards. */
private suspend fun normanBattle(ctx: ScriptContext) {
  ctx.say(PetalburgCity_Gym.NormanIntro)
  if (ctx.trainerBattle(TRAINER_NORMAN_1) != BattleResult.VICTORY) return
  ctx.say(PetalburgCity_Gym.NormanDefeat)
  ctx.say(PetalburgCity_Gym.ReceivedBalanceBadge)
  ctx.say(PetalburgCity_Gym.ExplainBalanceBadgeTakeThis)
  ctx.setFlag(HoennFlags.FLAG_DEFEATED_PETALBURG_GYM)
  ctx.setVar(HoennVars.VAR_PETALBURG_GYM_STATE, 7)
  ctx.setFlag(HoennFlags.FLAG_BADGE05_GET)
  ctx.setFlag(HoennFlags.FLAG_HIDE_MAUVILLE_GYM_WATTSON)
  ctx.clearFlag(HoennFlags.FLAG_HIDE_MAUVILLE_CITY_WATTSON)
  ctx.clearFlag(HoennFlags.FLAG_HIDE_DEWFORD_HALL_SLUDGE_BOMB_MAN)
  giveFacade(ctx)
  ctx.say(PetalburgCity_Gym.DadHappyAndSad)
  ctx.badgeAwarded(5)
}

private suspend fun giveFacade(ctx: ScriptContext) {
  if (!ctx.giveItem(Items.TM42)) return
  ctx.setFlag(HoennFlags.FLAG_RECEIVED_TM_FACADE)
  ctx.say(PetalburgCity_Gym.ExplainFacade)
}

private const val TRAINER_NORMAN_1 = 269

private suspend fun beginWallyTutorial(ctx: ScriptContext) {
  ctx.sayNpc(LOCALID_NORMAN, PetalburgCity_Gym.DadYoureHereWithYourPokemon)
  ctx.clearFlag(HoennFlags.FLAG_HIDE_PETALBURG_GYM_WALLY)
  ctx.showNpcAt(LOCALID_WALLY, 4, 111)
  ctx.sayNpc(LOCALID_WALLY, PetalburgCity_Gym.WallyIdLikeAPokemon)
  ctx.sayNpc(LOCALID_NORMAN, PetalburgCity_Gym.DadOhYoureWallyRight)
  ctx.sayNpc(LOCALID_WALLY, PetalburgCity_Gym.WallyIveNeverCaughtAPokemon)
  ctx.sayNpc(LOCALID_NORMAN, PetalburgCity_Gym.DadHmISee)
  ctx.sayNpc(LOCALID_NORMAN, PetalburgCity_Gym.DadPlayerGoWithWally)
  ctx.sayNpc(LOCALID_NORMAN, PetalburgCity_Gym.IllLoanYouMyZigzagoon)
  ctx.sayNpc(LOCALID_WALLY, PetalburgCity_Gym.WallyThankYouAndDadGivesPokeBall)
  ctx.sayNpc(LOCALID_WALLY, PetalburgCity_Gym.WallyOhWowThankYou)
  ctx.sayNpc(LOCALID_WALLY, PetalburgCity_Gym.WouldYouReallyComeWithMe)

  // PokeMMO intentionally skips the GBA catching demonstration. Retain its ROM-backed
  // conversation and advance directly to the post-tutorial state without modifying the party.
  ctx.setFlag(HoennFlags.FLAG_HIDE_PETALBURG_CITY_WALLYS_MOM)
  ctx.setVar(HoennVars.VAR_PETALBURG_CITY_STATE, 3)
  ctx.setVar(HoennVars.VAR_PETALBURG_GYM_STATE, 1)
  ctx.setFlag(HoennFlags.FLAG_HIDE_LITTLEROOT_TOWN_BIRCHS_LAB_RIVAL)
  PetalburgCity_Gym_EventScript_ReturnFromWallyTutorial.run(ctx)
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_MARY, PetalburgCity_Gym_Text_MaryIntro, PetalburgCity_Gym_Text_MaryDefeat, PetalburgCity_Gym_EventScript_SlideOpenAccuracyRoomDoors
 * goto_if_set FLAG_DEFEATED_PETALBURG_GYM, PetalburgCity_Gym_EventScript_MaryPostBadge
 * msgbox PetalburgCity_Gym_Text_MaryPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object PetalburgCity_Gym_EventScript_Mary : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_MARY
    ctx.gymTrainer(89, PetalburgCity_Gym.MaryIntro, PetalburgCity_Gym.MaryDefeat, PetalburgCity_Gym.MaryPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_RANDALL, PetalburgCity_Gym_Text_RandallIntro, PetalburgCity_Gym_Text_RandallDefeat, PetalburgCity_Gym_EventScript_SlideOpenSpeedRoomDoors
 * goto_if_set FLAG_DEFEATED_PETALBURG_GYM, PetalburgCity_Gym_EventScript_RandallPostBadge
 * msgbox PetalburgCity_Gym_Text_RandallPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object PetalburgCity_Gym_EventScript_Randall : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_RANDALL
    ctx.gymTrainer(71, PetalburgCity_Gym.RandallIntro, PetalburgCity_Gym.RandallDefeat, PetalburgCity_Gym.RandallPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_PARKER, PetalburgCity_Gym_Text_ParkerIntro, PetalburgCity_Gym_Text_ParkerDefeat, PetalburgCity_Gym_EventScript_SlideOpenConfusionRoomDoors
 * goto_if_set FLAG_DEFEATED_PETALBURG_GYM, PetalburgCity_Gym_EventScript_ParkerPostBadge
 * msgbox PetalburgCity_Gym_Text_ParkerPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object PetalburgCity_Gym_EventScript_Parker : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_PARKER
    ctx.gymTrainer(72, PetalburgCity_Gym.ParkerIntro, PetalburgCity_Gym.ParkerDefeat, PetalburgCity_Gym.ParkerPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_ALEXIA, PetalburgCity_Gym_Text_AlexiaIntro, PetalburgCity_Gym_Text_AlexiaDefeat, PetalburgCity_Gym_EventScript_SlideOpenDefenseRoomDoors
 * goto_if_set FLAG_DEFEATED_PETALBURG_GYM, PetalburgCity_Gym_EventScript_AlexiaPostBadge
 * msgbox PetalburgCity_Gym_Text_AlexiaPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object PetalburgCity_Gym_EventScript_Alexia : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_ALEXIA
    ctx.gymTrainer(90, PetalburgCity_Gym.AlexiaIntro, PetalburgCity_Gym.AlexiaDefeat, PetalburgCity_Gym.AlexiaPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_GEORGE, PetalburgCity_Gym_Text_GeorgeIntro, PetalburgCity_Gym_Text_GeorgeDefeat, PetalburgCity_Gym_EventScript_SlideOpenRecoveryRoomDoors
 * goto_if_set FLAG_DEFEATED_PETALBURG_GYM, PetalburgCity_Gym_EventScript_GeorgePostBadge
 * msgbox PetalburgCity_Gym_Text_GeorgePostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object PetalburgCity_Gym_EventScript_George : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_GEORGE
    ctx.gymTrainer(73, PetalburgCity_Gym.GeorgeIntro, PetalburgCity_Gym.GeorgeDefeat, PetalburgCity_Gym.GeorgePostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_JODY, PetalburgCity_Gym_Text_JodyIntro, PetalburgCity_Gym_Text_JodyDefeat, PetalburgCity_Gym_EventScript_SlideOpenStrengthRoomDoors
 * goto_if_set FLAG_DEFEATED_PETALBURG_GYM, PetalburgCity_Gym_EventScript_JodyPostBadge
 * msgbox PetalburgCity_Gym_Text_JodyPostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object PetalburgCity_Gym_EventScript_Jody : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_JODY
    ctx.gymTrainer(91, PetalburgCity_Gym.JodyIntro, PetalburgCity_Gym.JodyDefeat, PetalburgCity_Gym.JodyPostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * trainerbattle_single TRAINER_BERKE, PetalburgCity_Gym_Text_BerkeIntro, PetalburgCity_Gym_Text_BerkeDefeat, PetalburgCity_Gym_EventScript_SlideOpenOHKORoomDoors
 * goto_if_set FLAG_DEFEATED_PETALBURG_GYM, PetalburgCity_Gym_EventScript_BerkePostBadge
 * msgbox PetalburgCity_Gym_Text_BerkePostBattle, MSGBOX_AUTOCLOSE
 * end
 * ```
 */
internal object PetalburgCity_Gym_EventScript_Berke : Script {
  override suspend fun run(ctx: ScriptContext) {
    // TRAINER_BERKE
    ctx.gymTrainer(74, PetalburgCity_Gym.BerkeIntro, PetalburgCity_Gym.BerkeDefeat, PetalburgCity_Gym.BerkePostBattle)
  }
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lock
 * faceplayer
 * goto_if_set FLAG_DEFEATED_PETALBURG_GYM, PetalburgCity_Gym_EventScript_GymGuidePostVictory
 * msgbox PetalburgCity_Gym_Text_GymGuideAdvice, MSGBOX_DEFAULT
 * release
 * end
 * ```
 */
internal object PetalburgCity_Gym_EventScript_GymGuide : Script {
  override suspend fun run(ctx: ScriptContext) = TODO("port PetalburgCity_Gym_EventScript_GymGuide")
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lockall
 * goto_if_lt VAR_PETALBURG_GYM_STATE, 6, PetalburgCity_Gym_EventScript_DoorLocked
 * setvar VAR_0x8008, 7
 * setvar VAR_0x8009, 85
 * msgbox PetalburgCity_Gym_Text_EnterSpeedRoom, MSGBOX_YESNO
 * goto_if_eq VAR_RESULT, YES, PetalburgCity_Gym_EventScript_EnterRoom
 * goto_if_eq VAR_RESULT, NO, PetalburgCity_Gym_EventScript_DontEnterRoom
 * end
 * ```
 */
internal object PetalburgCity_Gym_EventScript_SpeedRoomDoor : Script {
  override suspend fun run(ctx: ScriptContext) =
      TODO("port PetalburgCity_Gym_EventScript_SpeedRoomDoor")
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lockall
 * goto_if_lt VAR_PETALBURG_GYM_STATE, 6, PetalburgCity_Gym_EventScript_DoorLocked
 * setvar VAR_0x8008, 1
 * setvar VAR_0x8009, 98
 * msgbox PetalburgCity_Gym_Text_EnterAccuracyRoom, MSGBOX_YESNO
 * goto_if_eq VAR_RESULT, YES, PetalburgCity_Gym_EventScript_EnterRoom
 * goto_if_eq VAR_RESULT, NO, PetalburgCity_Gym_EventScript_DontEnterRoom
 * end
 * ```
 */
internal object PetalburgCity_Gym_EventScript_AccuracyRoomDoor : Script {
  override suspend fun run(ctx: ScriptContext) =
      TODO("port PetalburgCity_Gym_EventScript_AccuracyRoomDoor")
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lockall
 * goto_if_not_defeated TRAINER_RANDALL, PetalburgCity_Gym_EventScript_DoorLocked
 * setvar VAR_0x8008, 7
 * setvar VAR_0x8009, 46
 * msgbox PetalburgCity_Gym_Text_EnterConfusionRoom, MSGBOX_YESNO
 * goto_if_eq VAR_RESULT, YES, PetalburgCity_Gym_EventScript_EnterRoom
 * goto_if_eq VAR_RESULT, NO, PetalburgCity_Gym_EventScript_DontEnterRoom
 * end
 * ```
 */
internal object PetalburgCity_Gym_EventScript_ConfusionRoomDoor : Script {
  override suspend fun run(ctx: ScriptContext) =
      TODO("port PetalburgCity_Gym_EventScript_ConfusionRoomDoor")
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lockall
 * goto_if_not_defeated TRAINER_RANDALL, PetalburgCity_Gym_EventScript_DoorLocked
 * setvar VAR_0x8008, 1
 * setvar VAR_0x8009, 59
 * msgbox PetalburgCity_Gym_Text_EnterDefenseRoom, MSGBOX_YESNO
 * goto_if_eq VAR_RESULT, YES, PetalburgCity_Gym_EventScript_EnterRoom
 * goto_if_eq VAR_RESULT, NO, PetalburgCity_Gym_EventScript_DontEnterRoom
 * end
 * ```
 */
internal object PetalburgCity_Gym_EventScript_LeftDefenseRoomDoor : Script {
  override suspend fun run(ctx: ScriptContext) =
      TODO("port PetalburgCity_Gym_EventScript_LeftDefenseRoomDoor")
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lockall
 * goto_if_not_defeated TRAINER_MARY, PetalburgCity_Gym_EventScript_DoorLocked
 * setvar VAR_0x8008, 7
 * setvar VAR_0x8009, 59
 * msgbox PetalburgCity_Gym_Text_EnterDefenseRoom, MSGBOX_YESNO
 * goto_if_eq VAR_RESULT, YES, PetalburgCity_Gym_EventScript_EnterRoom
 * goto_if_eq VAR_RESULT, NO, PetalburgCity_Gym_EventScript_DontEnterRoom
 * end
 * ```
 */
internal object PetalburgCity_Gym_EventScript_RightDefenseRoomDoor : Script {
  override suspend fun run(ctx: ScriptContext) =
      TODO("port PetalburgCity_Gym_EventScript_RightDefenseRoomDoor")
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lockall
 * goto_if_not_defeated TRAINER_MARY, PetalburgCity_Gym_EventScript_DoorLocked
 * setvar VAR_0x8008, 1
 * setvar VAR_0x8009, 72
 * msgbox PetalburgCity_Gym_Text_EnterRecoveryRoom, MSGBOX_YESNO
 * goto_if_eq VAR_RESULT, YES, PetalburgCity_Gym_EventScript_EnterRoom
 * goto_if_eq VAR_RESULT, NO, PetalburgCity_Gym_EventScript_DontEnterRoom
 * end
 * ```
 */
internal object PetalburgCity_Gym_EventScript_RecoveryRoomDoor : Script {
  override suspend fun run(ctx: ScriptContext) =
      TODO("port PetalburgCity_Gym_EventScript_RecoveryRoomDoor")
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lockall
 * goto_if_not_defeated TRAINER_PARKER, PetalburgCity_Gym_EventScript_DoorLocked
 * setvar VAR_0x8008, 1
 * setvar VAR_0x8009, 20
 * msgbox PetalburgCity_Gym_Text_EnterStrengthRoom, MSGBOX_YESNO
 * goto_if_eq VAR_RESULT, YES, PetalburgCity_Gym_EventScript_EnterRoom
 * goto_if_eq VAR_RESULT, NO, PetalburgCity_Gym_EventScript_DontEnterRoom
 * end
 * ```
 */
internal object PetalburgCity_Gym_EventScript_LeftStrengthRoomDoor : Script {
  override suspend fun run(ctx: ScriptContext) =
      TODO("port PetalburgCity_Gym_EventScript_LeftStrengthRoomDoor")
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lockall
 * goto_if_not_defeated TRAINER_ALEXIA, PetalburgCity_Gym_EventScript_DoorLocked
 * setvar VAR_0x8008, 7
 * setvar VAR_0x8009, 20
 * msgbox PetalburgCity_Gym_Text_EnterStrengthRoom, MSGBOX_YESNO
 * goto_if_eq VAR_RESULT, YES, PetalburgCity_Gym_EventScript_EnterRoom
 * goto_if_eq VAR_RESULT, NO, PetalburgCity_Gym_EventScript_DontEnterRoom
 * end
 * ```
 */
internal object PetalburgCity_Gym_EventScript_RightStrengthRoomDoor : Script {
  override suspend fun run(ctx: ScriptContext) =
      TODO("port PetalburgCity_Gym_EventScript_RightStrengthRoomDoor")
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lockall
 * goto_if_not_defeated TRAINER_ALEXIA, PetalburgCity_Gym_EventScript_DoorLocked
 * setvar VAR_0x8008, 1
 * setvar VAR_0x8009, 33
 * msgbox PetalburgCity_Gym_Text_EnterOHKORoom, MSGBOX_YESNO
 * goto_if_eq VAR_RESULT, YES, PetalburgCity_Gym_EventScript_EnterRoom
 * goto_if_eq VAR_RESULT, NO, PetalburgCity_Gym_EventScript_DontEnterRoom
 * end
 * ```
 */
internal object PetalburgCity_Gym_EventScript_LeftOHKORoomDoor : Script {
  override suspend fun run(ctx: ScriptContext) =
      TODO("port PetalburgCity_Gym_EventScript_LeftOHKORoomDoor")
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lockall
 * goto_if_not_defeated TRAINER_GEORGE, PetalburgCity_Gym_EventScript_DoorLocked
 * setvar VAR_0x8008, 7
 * setvar VAR_0x8009, 33
 * msgbox PetalburgCity_Gym_Text_EnterOHKORoom, MSGBOX_YESNO
 * goto_if_eq VAR_RESULT, YES, PetalburgCity_Gym_EventScript_EnterRoom
 * goto_if_eq VAR_RESULT, NO, PetalburgCity_Gym_EventScript_DontEnterRoom
 * end
 * ```
 */
internal object PetalburgCity_Gym_EventScript_RightOHKORoomDoor : Script {
  override suspend fun run(ctx: ScriptContext) =
      TODO("port PetalburgCity_Gym_EventScript_RightOHKORoomDoor")
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lockall
 * goto_if_not_defeated TRAINER_JODY, PetalburgCity_Gym_EventScript_DoorLocked
 * setvar VAR_0x8008, 1
 * setvar VAR_0x8009, 7
 * msgbox PetalburgCity_Gym_Text_EnterGymLeadersRoom, MSGBOX_YESNO
 * goto_if_eq VAR_RESULT, YES, PetalburgCity_Gym_EventScript_EnterRoom
 * goto_if_eq VAR_RESULT, NO, PetalburgCity_Gym_EventScript_DontEnterRoom
 * end
 * ```
 */
internal object PetalburgCity_Gym_EventScript_LeftGymLeadersRoomDoor : Script {
  override suspend fun run(ctx: ScriptContext) =
      TODO("port PetalburgCity_Gym_EventScript_LeftGymLeadersRoomDoor")
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lockall
 * goto_if_not_defeated TRAINER_BERKE, PetalburgCity_Gym_EventScript_DoorLocked
 * setvar VAR_0x8008, 7
 * setvar VAR_0x8009, 7
 * msgbox PetalburgCity_Gym_Text_EnterGymLeadersRoom, MSGBOX_YESNO
 * goto_if_eq VAR_RESULT, YES, PetalburgCity_Gym_EventScript_EnterRoom
 * goto_if_eq VAR_RESULT, NO, PetalburgCity_Gym_EventScript_DontEnterRoom
 * end
 * ```
 */
internal object PetalburgCity_Gym_EventScript_RightGymLeadersRoomDoor : Script {
  override suspend fun run(ctx: ScriptContext) =
      TODO("port PetalburgCity_Gym_EventScript_RightGymLeadersRoomDoor")
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lockall
 * goto_if_set FLAG_BADGE05_GET, PetalburgCity_Gym_EventScript_GymStatueCertified
 * goto PetalburgCity_Gym_EventScript_GymStatue
 * end
 * ```
 */
internal object PetalburgCity_Gym_EventScript_LeftGymStatue : Script {
  override suspend fun run(ctx: ScriptContext) =
      TODO("port PetalburgCity_Gym_EventScript_LeftGymStatue")
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lockall
 * goto_if_set FLAG_BADGE05_GET, PetalburgCity_Gym_EventScript_GymStatueCertified
 * goto PetalburgCity_Gym_EventScript_GymStatue
 * end
 * ```
 */
internal object PetalburgCity_Gym_EventScript_RightGymStatue : Script {
  override suspend fun run(ctx: ScriptContext) =
      TODO("port PetalburgCity_Gym_EventScript_RightGymStatue")
}

internal val PetalburgCity_GymScripts: Map<String, Script> =
    mapOf(
        "PetalburgCity_Gym_OnTransition" to PetalburgCity_Gym_OnTransition,
        "PetalburgCity_Gym_EventScript_ReturnFromWallyTutorial" to
            PetalburgCity_Gym_EventScript_ReturnFromWallyTutorial,
        "PetalburgCity_Gym_EventScript_Norman" to PetalburgCity_Gym_EventScript_Norman,
        "PetalburgCity_Gym_EventScript_Mary" to PetalburgCity_Gym_EventScript_Mary,
        "PetalburgCity_Gym_EventScript_Randall" to PetalburgCity_Gym_EventScript_Randall,
        "PetalburgCity_Gym_EventScript_Parker" to PetalburgCity_Gym_EventScript_Parker,
        "PetalburgCity_Gym_EventScript_Alexia" to PetalburgCity_Gym_EventScript_Alexia,
        "PetalburgCity_Gym_EventScript_George" to PetalburgCity_Gym_EventScript_George,
        "PetalburgCity_Gym_EventScript_Jody" to PetalburgCity_Gym_EventScript_Jody,
        "PetalburgCity_Gym_EventScript_Berke" to PetalburgCity_Gym_EventScript_Berke,
        "PetalburgCity_Gym_EventScript_GymGuide" to PetalburgCity_Gym_EventScript_GymGuide,
        "PetalburgCity_Gym_EventScript_SpeedRoomDoor" to
            PetalburgCity_Gym_EventScript_SpeedRoomDoor,
        "PetalburgCity_Gym_EventScript_AccuracyRoomDoor" to
            PetalburgCity_Gym_EventScript_AccuracyRoomDoor,
        "PetalburgCity_Gym_EventScript_ConfusionRoomDoor" to
            PetalburgCity_Gym_EventScript_ConfusionRoomDoor,
        "PetalburgCity_Gym_EventScript_LeftDefenseRoomDoor" to
            PetalburgCity_Gym_EventScript_LeftDefenseRoomDoor,
        "PetalburgCity_Gym_EventScript_RightDefenseRoomDoor" to
            PetalburgCity_Gym_EventScript_RightDefenseRoomDoor,
        "PetalburgCity_Gym_EventScript_RecoveryRoomDoor" to
            PetalburgCity_Gym_EventScript_RecoveryRoomDoor,
        "PetalburgCity_Gym_EventScript_LeftStrengthRoomDoor" to
            PetalburgCity_Gym_EventScript_LeftStrengthRoomDoor,
        "PetalburgCity_Gym_EventScript_RightStrengthRoomDoor" to
            PetalburgCity_Gym_EventScript_RightStrengthRoomDoor,
        "PetalburgCity_Gym_EventScript_LeftOHKORoomDoor" to
            PetalburgCity_Gym_EventScript_LeftOHKORoomDoor,
        "PetalburgCity_Gym_EventScript_RightOHKORoomDoor" to
            PetalburgCity_Gym_EventScript_RightOHKORoomDoor,
        "PetalburgCity_Gym_EventScript_LeftGymLeadersRoomDoor" to
            PetalburgCity_Gym_EventScript_LeftGymLeadersRoomDoor,
        "PetalburgCity_Gym_EventScript_RightGymLeadersRoomDoor" to
            PetalburgCity_Gym_EventScript_RightGymLeadersRoomDoor,
        "PetalburgCity_Gym_EventScript_LeftGymStatue" to
            PetalburgCity_Gym_EventScript_LeftGymStatue,
        "PetalburgCity_Gym_EventScript_RightGymStatue" to
            PetalburgCity_Gym_EventScript_RightGymStatue,
    )
