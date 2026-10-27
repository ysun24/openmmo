package de.fiereu.openmmo.server.game.script.vm

import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.server.game.script.ScriptContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.serialization.json.put

/**
 * Field specials (field_specials.c, field_effect.c, script_menu.c, elevators, transport, screen
 * effects, the puzzles' bookkeeping). Screen and camera effects go to the browser as 0xCE `fx`.
 */
@Singleton
class WorldSpecials @Inject constructor() : SpecialTable() {
  private suspend fun fx(ctx: ScriptContext, kind: String, ms: Long = 0, extra: Map<String, Int> = emptyMap()) {
    ctx.ui("fx") {
      put("kind", kind)
      for ((k, v) in extra) put(k, v)
    }
    if (ms > 0 && ctx.vm?.immediate != true) delay(ms)
  }

  init {
    // ---- screen and camera
    on("ShakeCamera", "ShakeScreen") { ctx ->
      // 0x8004 / 0x8005: vertical / horizontal pan, 0x8006 shakes, 0x8007 delay between them.
      val shakes = ctx.specialVar(6).coerceIn(1, 30)
      val frames = ctx.specialVar(7).coerceIn(1, 30)
      fx(ctx, "shake", shakes * frames * 1000L / 60, mapOf("x" to ctx.specialVar(5), "y" to ctx.specialVar(4), "ms" to shakes * frames * 1000 / 60))
      0
    }
    on("DoSealedChamberShakingEffect_Short", "DoSealedChamberShakingEffect_Long", "StartMirageTowerShake",
        "DoMirageTowerCeilingCrumble", "StartMirageTowerDisintegration", "StartMirageTowerFossilFallAndSink",
        "StartPlayerDescendMirageTower") { ctx ->
      fx(ctx, "shake", 1200, mapOf("x" to 2, "y" to 2, "ms" to 1200))
      0
    }
    noop("DrawWholeMapView", "SpawnCameraObject", "RemoveCameraObject", "Script_ClearHeldMovement",
        "DisableMsgBoxWalkaway", "WaitWeather", "LoopWingFlapSE", "LoopWingFlapSound",
        "SetDeoxysRockPalette", "SetDeoxysTrianglePalette", "DestroyMewEmergingGrassSprite",
        "SetMewAboveGrass", "InitSecretBaseDecorationSprites", "SetMauvilleOldManObjEventGfx",
        "SetLilycoveLadyGfx", "SetSecretBaseOwnerGfxId", "SpawnLinkPartnerObjectEvent",
        "UpdateTrainerCardPhotoIcons", "BrailleCursorToggle", "AnimateTeleporterCable",
        "AnimateTeleporterHousing", "LookThroughPorthole", "DoPicboxCancel", "RemoveRecordsWindow")
    on("DoPCTurnOnEffect", "AnimatePcTurnOn") { ctx ->
      fx(ctx, "pc_on")
      0
    }
    on("DoPCTurnOffEffect", "AnimatePcTurnOff", "DoSecretBasePCTurnOffEffect") { ctx ->
      fx(ctx, "pc_off")
      0
    }
    on("TurnOnTVScreen", "TurnOffTVScreen") { ctx ->
      fx(ctx, if (ctx.vm?.currentSpecial == "TurnOnTVScreen") "tv_on" else "tv_off")
      0
    }
    on("DoOrbEffect") { ctx ->
      fx(ctx, "orb", 800)
      0
    }
    on("FadeOutOrbEffect") { ctx ->
      fx(ctx, "orb_end", 400)
      0
    }
    on("DoPokemonLeagueLightingEffect") { ctx ->
      fx(ctx, "league_lights")
      0
    }
    on("Script_DoRayquazaScene") { ctx ->
      fx(ctx, "rayquaza", 2500)
      0
    }
    on("DoLotteryCornerComputerEffect", "EndLotteryCornerComputerEffect") { ctx -> 0 }
    on("ShowMapNamePopup") { ctx ->
      ctx.vmCurrentMap()?.let { m -> ctx.ui("fx") { put("kind", "map_name"); put("name", m.mapsec) } }
      0
    }
    on("Script_FacePlayer") { ctx ->
      val vm = ctx.vm ?: return@on 0
      val npc = vm.poseOf(vm.lastTalked) ?: return@on 0
      val me = vm.poseOf(ScriptVm.OBJ_PLAYER) ?: return@on 0
      val d =
          if (me.x > npc.x) Direction.RIGHT
          else if (me.x < npc.x) Direction.LEFT
          else if (me.y < npc.y) Direction.UP
          else Direction.DOWN
      ctx.vmTurnNpc(vm.lastTalked, d)
      0
    }
    on("Script_FadeOutMapMusic") { ctx ->
      ctx.ui("sound") { put("kind", "fadeout") }
      0
    }
    on("ShowFieldMessageStringVar4") { ctx ->
      val vm = ctx.vm ?: return@on 0
      vm.showMessage(ScriptText(0, 0, "gStringVar4", false, vm.state.stringVar4))
      0
    }

    // ---- menus
    // FRLG ListMenu: VAR_0x8004 = LISTMENU_* (sListMenuLabels). Result in VAR_RESULT (0x7F = B).
    on("ListMenu", "ReturnToListMenu") { ctx ->
      val vm = ctx.vm ?: return@on 0
      val items = vm.img.stringTables["sListMenuLabels"]?.get(ctx.specialVar(4)).orEmpty()
      ctx.setResult(if (items.isEmpty()) 0x7F else ctx.vmMenu("multichoice", items, 0, 0x7F, 1))
      0
    }
    // Emerald scrollable multichoice: VAR_0x8004 = SCROLL_MULTI_*.
    on("ShowScrollableMultichoice") { ctx ->
      val vm = ctx.vm ?: return@on 0
      val items = vm.img.stringTables["sScrollableMultichoiceOptions"]?.get(ctx.specialVar(4)).orEmpty()
      ctx.setResult(
          if (items.isEmpty()) ScriptVm.MULTI_B_PRESSED
          else ctx.vmMenu("multichoice", items, 0, ScriptVm.MULTI_B_PRESSED, 1))
      0
    }

    // ---- elevators
    on("InitElevatorFloorSelectMenuPos", "GetDeptStoreDefaultFloorChoice") { ctx -> 0 }
    on("GetElevatorFloor") { ctx ->
      // The floor the elevator is on, for the "current floor" window (FRLG 0x8005).
      ctx.setSpecialVar(5, ctx.specialVar(5))
      0
    }
    on("DrawElevatorCurrentFloorWindow", "ShowDeptStoreElevatorFloorSelect") { ctx -> 0 }
    on("CloseElevatorCurrentFloorWindow", "CloseDeptStoreElevatorWindow") { ctx -> 0 }
    on("AnimateElevator", "MoveElevator") { ctx ->
      // 0x8005 floor it is on, 0x8006 where it goes: one shake per floor.
      val floors = kotlin.math.abs(ctx.specialVar(5) - ctx.specialVar(6)).coerceIn(1, 10)
      fx(ctx, "shake", floors * 300L, mapOf("x" to 0, "y" to 1, "ms" to floors * 300))
      0
    }
    on("SetDeptStoreFloor") { ctx -> 0 }

    // ---- transport
    on("CableCarWarp") { ctx ->
      val up = ctx.specialVar(4) != 0
      ctx.vm?.state?.warps?.put("cablecar", intArrayOf(if (up) 1 else 0))
      0
    }
    on("CableCar") { ctx ->
      val up = ctx.vm?.state?.warps?.get("cablecar")?.firstOrNull() == 1
      fx(ctx, "cable_car", 2000)
      warpByName(ctx, if (up) "Route112_CableCarStation" else "MtChimney_CableCarStation", 6, 4)
      0
    }
    on("DrawSeagallopDestinationMenu") { ctx ->
      val dests = SEAGALLOP_NAMES.filterIndexed { i, _ -> i != ctx.specialVar(4) }
      val pick = ctx.vmMenu("multichoice", dests + "EXIT", 0, SCR_MENU_CANCEL, 1)
      ctx.setResult(if (pick >= dests.size) SCR_MENU_CANCEL else pick)
      0
    }
    on("GetSelectedSeagallopDestination") { ctx ->
      val r = ctx.result
      if (r == SCR_MENU_CANCEL) return@on SCR_MENU_CANCEL
      val dests = SEAGALLOP_NAMES.indices.filter { it != ctx.specialVar(4) }
      dests.getOrElse(r) { SCR_MENU_CANCEL }
    }
    on("GetSeagallopNumber") { ctx -> 1 }
    on("DoSeagallopFerryScene") { ctx ->
      fx(ctx, "ferry", 2500)
      val dest = ctx.specialVar(6)
      val (map, x, y) = SEAGALLOP_ARRIVALS.getOrElse(dest) { SEAGALLOP_ARRIVALS[0] }
      warpByName(ctx, map, x, y)
      0
    }
    on("DoSSAnneDepartureCutscene") { ctx ->
      fx(ctx, "ssanne", 2500)
      0
    }
    on("ScriptMenu_CreateLilycoveSSTidalMultichoice") { ctx ->
      val names = ctx.vm?.img?.stringTables?.get("sLilycoveSSTidalDestinations")?.get(0)
          ?: listOf("SLATEPORT CITY", "BATTLE FRONTIER", "SOUTHERN ISLAND", "NAVEL ROCK", "BIRTH ISLAND", "FARAWAY ISLAND")
      val avail = buildList {
        add(0)
        if (ctx.isFlagSet("hoenn/FLAG_SYS_GAME_CLEAR")) add(1)
        if (ctx.isFlagSet("hoenn/FLAG_ENABLE_SHIP_SOUTHERN_ISLAND")) add(2)
      }
      val pick = ctx.vmMenu("multichoice", avail.map { names.getOrElse(it) { "?" } } + "EXIT", 0, 127, 1)
      ctx.vm?.state?.warps?.put("sstidal", intArrayOf(if (pick < avail.size) avail[pick] else 127))
      0
    }
    on("GetLilycoveSSTidalSelection") { ctx -> ctx.vm?.state?.warps?.get("sstidal")?.firstOrNull() ?: 127 }
    on("SetSSTidalFlag") { ctx ->
      ctx.setFlag("hoenn/FLAG_SYS_CRUISE_MODE")
      0
    }
    on("ResetSSTidalFlag") { ctx ->
      ctx.clearFlag("hoenn/FLAG_SYS_CRUISE_MODE")
      0
    }
    on("DoFallWarp") { ctx ->
      val w = ctx.vm?.state?.warps?.get("setholewarp") ?: return@on 0
      ctx.vmWarp(w[0], w[1], 0xFF, ctx.state.x.toInt(), ctx.state.y.toInt(), "fallwarp")
      0
    }
    on("ForcePlayerOntoBike") { ctx ->
      ctx.state.travel = de.fiereu.openmmo.net.game.packets.field.TravelMode.MACH_BIKE
      0
    }
    on("ForcePlayerToStartSurfing") { ctx ->
      ctx.state.travel = de.fiereu.openmmo.net.game.packets.field.TravelMode.SURF
      0
    }
    on("GetPlayerAvatarBike") { ctx ->
      when (ctx.state.travel.name) {
        "ACRO_BIKE" -> 1
        "MACH_BIKE" -> 2
        else -> 0
      }
    }
    noop("SwapRegisteredBike")
    on("EnterSafariMode") { ctx ->
      ctx.setFlag("${ctx.regionName}/FLAG_SYS_SAFARI_MODE")
      ctx.setVar("${ctx.regionName}/OPENMMO_SAFARI_STEPS", if (ctx.vm?.isFrlg == true) 600 else 500)
      0
    }
    on("ExitSafariMode") { ctx ->
      ctx.clearFlag("${ctx.regionName}/FLAG_SYS_SAFARI_MODE")
      0
    }
    on("Special_BeginCyclingRoadChallenge") { ctx ->
      ctx.setVar("hoenn/OPENMMO_CYCLING_START", (System.currentTimeMillis() / 1000).toInt() and 0xFFFF)
      0
    }
    on("FinishCyclingRoadChallenge", "UpdateCyclingRoadState") { ctx -> 0 }
    on("GetRecordedCyclingRoadResults") { ctx -> 0 }

    // ---- puzzles and map state
    on("SetVermilionTrashCans") { ctx ->
      val first = Random.nextInt(15) + 1
      ctx.setSpecialVar(4, first)
      ctx.setSpecialVar(5, first + listOf(1, 5, -1, -5).random().let { if (first + it in 1..15) it else 1 })
      0
    }
    on("MauvilleGymSetDefaultBarriers", "MauvilleGymPressSwitch", "MauvilleGymDeactivatePuzzle",
        "PetalburgGymSlideOpenRoomDoors", "PetalburgGymUnlockRoomDoors",
        "SetSootopolisGymCrackedIceMetatiles", "SetIcefallCaveCrackedIceMetatiles",
        "RotatingGate_InitPuzzle", "RotatingGate_InitPuzzleAndGraphics", "SetMirageTowerVisibility",
        "UpdateShoalTideFlag", "TryUpdateRusturfTunnelState", "SetTrickHouseNuggetFlag",
        "ResetTrickHouseNuggetFlag", "SeafoamIslandsB4F_CurrentDumpsPlayerOnLand") { ctx ->
      puzzleState(ctx)
    }
    on("IsMirageIslandPresent") { ctx ->
      // Mirage Island shows when a party mon's personality low half matches the day's value.
      val v = ctx.getVar("hoenn/VAR_MIRAGE_RND_H")
      if (v != 0 && ctx.vmParty().any { (it.seed and 0xFFFF) == v }) 1 else 0
    }
    on("ShouldDoBrailleRegicePuzzle") { ctx -> 0 }
    on("DoDeoxysRockInteraction", "DoDeoxysTriangleInteraction") { ctx ->
      ctx.setResult(0)
      0
    }
    on("SetHiddenItemFlag") { ctx ->
      ctx.flagSet(ctx.specialVar(4))
      0
    }
    on("StorePlayerCoordsInVars") { ctx ->
      ctx.setSpecialVar(4, ctx.state.x.toInt())
      ctx.setSpecialVar(5, ctx.state.y.toInt())
      0
    }
    on("GetPlayerFacingDirection") { ctx -> ctx.vm?.dirCode(ctx.state.facingDirection) ?: 0 }
    on("SetWalkingIntoSignVars") { ctx -> 0 }
    on("IsPlayerLeftOfVermilionSailor") { ctx -> if (ctx.state.x <= SAILOR_X) 1 else 0 }
    on("GetMartClerkObjectId", "GetMartEmployeeObjectEventId") { ctx -> 1 }
    on("InitRoamer") { ctx ->
      ctx.setVar("${ctx.regionName}/OPENMMO_ROAMER", ctx.specialVar(4).coerceAtLeast(1))
      0
    }
    on("CreateAbnormalWeatherEvent") { ctx ->
      ctx.setVar("hoenn/VAR_ABNORMAL_WEATHER_LOCATION", Random.nextInt(1, 17))
      0
    }
    on("GetAbnormalWeatherMapNameAndType") { ctx ->
      val loc = ctx.getVar("hoenn/VAR_ABNORMAL_WEATHER_LOCATION")
      ctx.bufferString(1, ABNORMAL_ROUTES.getOrElse((loc - 1) / 2) { "ROUTE 114" })
      if (loc % 2 == 1) 1 else 0
    }
    on("SetRoute119Weather", "SetRoute123Weather") { ctx -> 0 }
    on("GetWeekCount") { ctx -> ((System.currentTimeMillis() / 86_400_000L) / 7).toInt().coerceAtMost(9999) }
    on("GetDaysUntilPacifidlogTMAvailable") { ctx ->
      val day = (System.currentTimeMillis() / 86_400_000L).toInt() and 0xFFFF
      val got = ctx.getVar("hoenn/VAR_PACIFIDLOG_TM_RECEIVED_DAY")
      if (got == 0 || day - got >= 7) 0 else 7 - (day - got)
    }
    on("SetPacifidlogTMReceivedDay") { ctx ->
      ctx.setVar("hoenn/VAR_PACIFIDLOG_TM_RECEIVED_DAY", (System.currentTimeMillis() / 86_400_000L).toInt() and 0xFFFF)
      0
    }
    on("ResetHealLocationFromDewford", "InitBirchState") { ctx -> 0 }
    on("FoundBlackGlasses", "FoundAbandonedShipRoom1Key", "FoundAbandonedShipRoom2Key",
        "FoundAbandonedShipRoom4Key", "FoundAbandonedShipRoom6Key") { ctx -> 0 }

    // ---- town map, save, clock, diploma, credits, hall of fame
    on("FieldShowRegionMap", "ShowTownMap") { ctx ->
      ctx.ui("townmap") { put("open", true) }
      0
    }
    on("SaveGame", "Field_AskSaveTheGame") { ctx ->
      // The server saves continuously; the save prompt always succeeds.
      ctx.state.characterId?.let { ctx.characters?.flushCharacterAsync(it) }
      ctx.setResult(1)
      0
    }
    on("StartWallClock", "Special_ViewWallClock") { ctx ->
      ctx.ui("clock") { put("open", true) }
      0
    }
    on("ShowDiploma", "Special_ShowDiploma") { ctx ->
      ctx.ui("diploma") { put("open", true) }
      0
    }
    on("DoCredits") { ctx ->
      fx(ctx, "credits", 3000)
      0
    }
    on("EnterHallOfFame") { ctx ->
      ctx.setFlag("${ctx.regionName}/FLAG_SYS_GAME_CLEAR")
      fx(ctx, "hall_of_fame", 3000)
      0
    }
    on("HallOfFamePCBeginFade") { ctx -> 0 }
    on("GameClear") { ctx ->
      ctx.setFlag("${ctx.regionName}/FLAG_SYS_GAME_CLEAR")
      0
    }
    on("SetChampionSaveWarp", "SetPostgameFlags") { ctx -> 0 }
    on("OpenPokenavForTutorial", "ScriptMenu_CreateStartMenuForPokenavTutorial") { ctx -> 0 }

    // ---- help system and quest log (FRLG): nothing to do on a server.
    noop("HelpSystem_Enable", "HelpSystem_Disable", "Script_SetHelpContext", "SetHelpContextForMap",
        "BackupHelpContext", "RestoreHelpContext", "QuestLog_CutRecording",
        "QuestLog_StartRecordingInputsAfterDeferredEvent", "SetUsedPkmnCenterQuestLogEvent")
    on("GetQuestLogState") { ctx -> 0 }
    on("SetFlavorTextFlagFromSpecialVars") { ctx -> 0 }
    on("StickerManGetBragFlags") { ctx ->
      ctx.setSpecialVar(4, 0)
      0
    }
    on("UpdateLoreleiDollCollection") { ctx -> 0 }
  }

  /** A puzzle's visual state lives in metatiles the scripts set themselves; nothing to add. */
  private fun puzzleState(ctx: ScriptContext): Int = 0

  private suspend fun warpByName(ctx: ScriptContext, name: String, x: Int, y: Int) {
    val maps = ctx.maps ?: return
    val m = maps.getMapByName(ctx.state.regionId, name) ?: return
    ctx.warp(ctx.state.regionId, m.bankId.toInt() and 0xFF, m.mapId.toInt() and 0xFF, x, y, ctx.state.facingDirection)
    ctx.vm?.stop()
  }

  private companion object {
    const val SCR_MENU_CANCEL = 127
    const val SAILOR_X = 23
    val SEAGALLOP_NAMES =
        listOf("VERMILION CITY", "ONE ISLAND", "TWO ISLAND", "THREE ISLAND", "FOUR ISLAND",
            "FIVE ISLAND", "SIX ISLAND", "SEVEN ISLAND")
    val SEAGALLOP_ARRIVALS =
        listOf(
            Triple("VermilionCity", 23, 32),
            Triple("OneIsland_Harbor", 8, 5),
            Triple("TwoIsland_Harbor", 8, 5),
            Triple("ThreeIsland_Harbor", 8, 5),
            Triple("FourIsland_Harbor", 8, 5),
            Triple("FiveIsland_Harbor", 8, 5),
            Triple("SixIsland_Harbor", 8, 5),
            Triple("SevenIsland_Harbor", 8, 5))
    val ABNORMAL_ROUTES =
        listOf("ROUTE 114", "ROUTE 115", "ROUTE 116", "ROUTE 118", "ROUTE 105", "ROUTE 125",
            "ROUTE 127", "ROUTE 129")
  }
}
