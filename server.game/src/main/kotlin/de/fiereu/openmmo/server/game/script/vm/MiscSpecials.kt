package de.fiereu.openmmo.server.game.script.vm

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Story specials that only fill strings or look at the player: rival / parent / big guy strings,
 * money held in VAR_0x8005, trainer card stars, the player's id digit.
 */
@Singleton
class StorySpecials @Inject constructor() : SpecialTable() {
  init {
    on("GetRivalSonDaughterString") { ctx ->
      ctx.bufferString(1, if (ctx.isFemale) "son" else "daughter")
      0
    }
    on("GetPlayerBigGuyGirlString", "BufferBigGuyOrBigGirlString") { ctx ->
      ctx.bufferString(1, if (ctx.isFemale) "big girl" else "big guy")
      0
    }
    on("GetMomOrDadStringForTVMessage") { ctx ->
      val dad = ctx.isFlagSet("hoenn/FLAG_BADGE05_GET")
      ctx.bufferString(1, if (dad) "DAD" else "MOM")
      0
    }
    on("ShowNatureGirlMessage") { ctx ->
      val m = ctx.vmParty().firstOrNull { !it.isEgg }
      ctx.bufferString(1, m?.nature?.name ?: "HARDY")
      0
    }
    on("IsEnoughForCostInVar0x8005") { ctx ->
      ctx.setResult(if (ctx.vmMoney() >= ctx.specialVar(5)) 1 else 0)
      0
    }
    on("SubtractMoneyFromVar0x8005") { ctx ->
      ctx.vmAddMoney(-ctx.specialVar(5))
      0
    }
    on("GetPlayerTrainerIdOnesDigit") { ctx -> ((ctx.state.characterId ?: 0L) % 10).toInt() }
    on("CountPlayerTrainerStars") { ctx ->
      var stars = 0
      if (ctx.isFlagSet("${ctx.regionName}/FLAG_SYS_GAME_CLEAR")) stars++
      if (ctx.isFlagSet("${ctx.regionName}/FLAG_SYS_NATIONAL_DEX")) stars++
      stars
    }
    on("SetBattledOwnerFromResult") { ctx -> 0 }
    on("GetRandomActiveShowIdx", "GetNextActiveShowIfMassOutbreak") { ctx -> 0 }
    on("RetrieveLotteryNumber") { ctx ->
      val n =
          ctx.getVar("hoenn/OPENMMO_LOTTERY_NUMBER").takeIf { it != 0 } ?: Random.nextInt(1, 65535)
      ctx.setVar("hoenn/OPENMMO_LOTTERY_NUMBER", n)
      ctx.setSpecialVar(4, n)
      0
    }
    on("BufferLottoTicketNumber") { ctx ->
      ctx.bufferString(1, "%05d".format(ctx.specialVar(4)))
      0
    }
    on("PickLotteryCornerTicket") { ctx ->
      // No other players' mons are known to the lottery; the draw never matches.
      ctx.setSpecialVar(4, 0)
      0
    }
  }
}

/**
 * The side systems of the two games' towns that need no UI of their own: TV, the Lilycove ladies
 * (favor / quiz), the Mauville old man, the fan clubs, link / cable club rooms (no link here: the
 * scripts take their "not connected" branch), secret base bookkeeping, Walda and the decoration
 * trader. Values follow the decomp's defaults for a save that never used the feature.
 */
@Singleton
class MiscSpecials @Inject constructor() : SpecialTable() {
  init {
    // ---- TV and news
    on(
        "DoTVShow",
        "DoTVShowInSearchOfTrainers",
        "DoPokeNews",
        "GetSelectedTVShow",
        "ResetTVShowState",
        "CheckForPlayersHouseNews",
        "TryPutLotteryWinnerReportOnAir",
        "TryPutNameRaterShowOnTheAir",
        "TryPutTrainerFanClubOnAir",
        "TryPutTreasureInvestigatorsOnAir",
        "PutFanClubSpecialOnTheAir",
        "IsGabbyAndTyShowOnTheAir",
        "GabbyAndTyGetBattleNum",
        "GabbyAndTyGetLastBattleTrivia",
        "GabbyAndTyGetLastQuote",
        "GetGabbyAndTyLocalIds") { ctx ->
          ctx.setResult(0)
          0
        }
    // ---- Lilycove favor / quiz lady (lilycove_lady.c)
    on("Script_GetLilycoveLadyId") { ctx -> ctx.getVar("hoenn/OPENMMO_LILYCOVE_LADY") }
    on("GetFavorLadyState", "GetQuizLadyState") { ctx -> 0 }
    on(
        "BufferFavorLadyRequest",
        "BufferFavorLadyItemName",
        "BufferFavorLadyPlayerName",
        "BufferQuizAuthorNameAndCheckIfLady",
        "BufferQuizCorrectAnswer",
        "BufferQuizPrizeItem",
        "BufferQuizPrizeName") { ctx ->
          ctx.bufferString(1, "")
          0
        }
    on(
        "Script_FavorLadyOpenBagMenu",
        "Script_QuizLadyOpenBagMenu",
        "Script_DoesFavorLadyLikeItem",
        "DidFavorLadyLikeItem",
        "IsFavorLadyThresholdMet",
        "HasAnotherPlayerGivenFavorLadyItem",
        "FavorLadyGetPrize",
        "SetFavorLadyState_Complete",
        "IsQuizAnswerCorrect",
        "IsQuizLadyWaitingForChallenger",
        "QuizLadyGetPlayerAnswer",
        "QuizLadyPickNewQuestion",
        "QuizLadyRecordCustomQuizData",
        "QuizLadySetCustomQuestion",
        "QuizLadySetWaitingForChallenger",
        "QuizLadyShowQuizQuestion",
        "QuizLadyTakePrizeForCustomQuiz",
        "SetQuizLadyState_Complete",
        "SetQuizLadyState_GivePrize",
        "ClearQuizLadyPlayerAnswer",
        "ClearQuizLadyQuestionAndAnswer",
        "GetQuizAuthor") { ctx ->
          ctx.setResult(0)
          0
        }
    // ---- Mauville old man (bard, hipster, trader, storyteller, giddy)
    on("Script_GetCurrentMauvilleMan") { ctx -> 0 }
    on(
        "PlayBardSong",
        "SaveBardSongLyrics",
        "HasBardSongBeenChanged",
        "HipsterTryTeachWord",
        "HasHipsterTaughtWord",
        "SetHipsterTaughtWord",
        "GenerateGiddyLine",
        "GiddyShouldTellAnotherTale",
        "StorytellerGetFreeStorySlot",
        "StorytellerStoryListMenu",
        "StorytellerUpdateStat",
        "Script_StorytellerDisplayStory",
        "Script_StorytellerInitializeRandomStat",
        "HasStorytellerAlreadyRecorded",
        "GetTraderTradedFlag",
        "TraderMenuGetDecoration",
        "TraderShowDecorationMenu",
        "TraderDoDecorationTrade",
        "BufferDeepLinkPhrase",
        "TryBufferWaldaPhrase",
        "TryGetWallpaperWithWaldaPhrase",
        "DoWaldaNamingScreen") { ctx ->
          ctx.setResult(0)
          0
        }
    // ---- trainer fan clubs (Lilycove, Saffron)
    on("GetNumFansOfPlayerInTrainerFanClub", "Script_GetNumFansOfPlayerInTrainerFanClub") { ctx ->
      1
    }
    on("IsFanClubMemberFanOfPlayer", "Script_IsFanClubMemberFanOfPlayer") { ctx ->
      ctx.setResult(0)
      0
    }
    on("BufferFanClubTrainerName", "Script_BufferFanClubTrainerName") { ctx ->
      ctx.bufferString(1, ctx.playerName)
      0
    }
    on(
        "Script_TryGainNewFanFromCounter",
        "TryLoseFansFromPlayTime",
        "Script_TryLoseFansFromPlayTime",
        "UpdateTrainerFanClubGameClear",
        "Script_UpdateTrainerFanClubGameClear") { ctx ->
          0
        }

    // ---- link rooms: there is no link cable; every attempt reports nobody connected.
    // IsWirelessAdapterConnected / TryJoinLinkGroup / TryBecomeLinkLeader are r5-minigames'
    // (LinkSpecials: the Wireless Club lobby).
    on("TryBattleLinkup", "TryTradeLinkup") { ctx ->
      ctx.setResult(LINKUP_FAILED)
      0
    }
    noop(
        "CloseLink",
        "CleanupLinkRoomState",
        "ExitLinkRoom",
        "ReturnFromLinkRoom",
        "EnterColosseumPlayerSpot",
        "ColosseumPlayerSpotTriggered",
        "EnterTradeSeat",
        "PlayerEnteredTradeSeat",
        "SetCableClubWarp",
        "DoCableClubWarp",
        "Script_ShowLinkTrainerCard",
        "ShowLinkBattleRecords")
    on("GetLinkPartnerNames") { ctx -> 0 }

    // ---- secret bases (r4 runs the bases themselves; this is the scripts' bookkeeping)
    on("CheckPlayerHasSecretBase") { ctx ->
      ctx.setResult(if (ctx.getVar("hoenn/OPENMMO_SECRET_BASE") != 0) 1 else 0)
      0
    }
    on(
        "GetSecretBaseTypeInFrontOfPlayer",
        "GetSecretBaseOwnerAndState",
        "GetSecretBaseNearbyMapName",
        "GetCurSecretBaseRegistrationValidity",
        "IsCurSecretBaseOwnedByAnotherPlayer",
        "CopyCurSecretBaseOwnerName_StrVar1",
        "ToggleCurSecretBaseRegistry",
        "SetPlayerSecretBase",
        "EnterSecretBase",
        "EnterNewlyCreatedSecretBase",
        "InitSecretBaseVars",
        "MoveOutOfSecretBase",
        "MoveOutOfSecretBaseFromOutside",
        "ClearAndLeaveSecretBase",
        "ShowSecretBaseDecorationMenu",
        "ShowSecretBaseRegistryMenu",
        "PutAwayDecorationIteration",
        "SetDecoration",
        "IsDecorationCategoryFull",
        "DoesPlayerHaveNoDecorations",
        "InteractWithShieldOrTVDecoration",
        "CheckInteractedWithFriendsCushionDecor",
        "CheckInteractedWithFriendsDollDecor",
        "CheckInteractedWithFriendsFurnitureBottom",
        "CheckInteractedWithFriendsFurnitureMiddle",
        "CheckInteractedWithFriendsFurnitureTop",
        "CheckInteractedWithFriendsPosterDecor",
        "CheckInteractedWithFriendsSandOrnament") { ctx ->
          ctx.setResult(0)
          0
        }
    // ---- misc
    on("IsEnigmaBerryValid") { ctx -> 0 }
    on("SetMatchCallRegisteredFlag") { ctx ->
      ctx.setFlag("hoenn/OPENMMO_MATCH_CALL_${ctx.specialVar(4)}")
      0
    }
  }

  private companion object {
    const val LINKUP_FAILED = 7
  }
}
