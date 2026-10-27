package de.fiereu.openmmo.server.game.contest

import de.fiereu.openmmo.dialog.generated.hoenn.BerryBlender
import de.fiereu.openmmo.dialog.generated.hoenn.BerryTree as BerryTreeText
import de.fiereu.openmmo.dialog.generated.hoenn.LilycoveCity_ContestLobby as Lobby
import de.fiereu.openmmo.dialog.generated.hoenn.LilycoveCity_LilycoveMuseum_2F as Museum2F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hand ports of the contest lane's decomp scripts, so the systems run before the decomp script
 * interpreter (r5-scripts) reaches them: the Lilycove contest reception and hall
 * (data/scripts/contest_hall.inc, LilycoveCity_ContestLobby), BerryTreeScript
 * (data/scripts/berry_tree.inc), the three Berry Blenders and the link blender
 * (data/scripts/berry_blender.inc), the lobby's hall paintings, the blender records and the
 * museum's exhibition hall (LilycoveCity_LilycoveMuseum_2F). Every special they call goes through
 * [ContestSpecials], the same handlers the interpreter calls. Once the interpreter runs these
 * labels, GameServerModule can drop [byLabel].
 */
@Singleton
class ContestLaneScripts
@Inject
constructor(private val lane: ContestLane, private val specials: ContestSpecials) {
  private suspend fun special(ctx: ScriptContext, name: String) = specials.run(ctx, name)

  private val v = SpecialVars

  // ---------------------------------------------------------------- contest reception

  /**
   * LilycoveCity_ContestLobby_EventScript_ContestReceptionist: SpeakToContestReceptionist (the
   * POKéBLOCK CASE on the first visit), the rank / category / monster menus, TryEnterContestMon,
   * then the hall: the contest, its results, the prize and the contest artist.
   */
  private val receptionist = Script { ctx ->
    lane.charId(ctx) ?: return@Script
    special(ctx, "ClearLinkContestFlags")
    if (!v.flag(ctx, FLAG_RECEIVED_POKEBLOCK_CASE)) {
      ctx.say(Lobby.ReceptionDontHavePokeblockCase)
      lane.item("POKEBLOCK_CASE")?.let { ctx.giveItem(it) }
      v.setFlag(ctx, FLAG_RECEIVED_POKEBLOCK_CASE)
      ctx.say(Lobby.NowThatWeveClearedThatUp)
    } else ctx.say(Lobby.ContestReception)
    if (!ctx.askYesNo(Lobby.EnterContest1)) {
      ctx.say(Lobby.ParticipateAnotherTime)
      return@Script
    }
    var entry: ContestEntry
    while (true) {
      ctx.say(Lobby.EnterWhichRank)
      entry =
          lane.contests.chooseEntry(ctx.session, ctx.state)
              ?: run {
                ctx.say(Lobby.ParticipateAnotherTime)
                return@Script
              }
      v.set(ctx, v.CONTEST_RANK, entry.rank)
      v.set(ctx, v.CONTEST_CATEGORY, entry.category)
      v.set(ctx, v.V8004, entry.slot)
      when (special(ctx, "TryEnterContestMon")) {
        Contest.CANT_ENTER -> ctx.say(Lobby.MonNotQualifiedForRank)
        Contest.CANT_ENTER_EGG -> ctx.say(Lobby.EggCannotTakePart)
        Contest.CANT_ENTER_FAINTED -> ctx.say(Lobby.MonInNoConditionForContest)
        Contest.CAN_ENTER_HIGH_RANK -> if (ctx.askYesNo(Lobby.AlreadyWonEnterAnyway)) break
        else -> break
      }
    }
    ctx.say(Lobby.YourMonIsEntryNum4)
    ctx.say(Lobby.ContestBeginShortly)
    // Matchmaking (the online entrants of this category and rank, NPCs for the other seats),
    // then the decomp's hall: LilycoveCity_ContestLobby_EventScript_SetContestType +
    // WarpToContestHall; the hall's ON_WARP / ON_FRAME (VAR_CONTEST_HALL_STATE 1) run
    // ContestHall_EventScript_Contest in the interpreter: the MC and judge walk on, the
    // contestants walk to the center one by one, the audience votes, startcontest (the appeals),
    // the judging, showcontestresults, the winner's prize and the exit warp back to the lobby
    // (whose ON_FRAME brings the contest artist).
    lane.contests.enterHall(
        ctx.session, ctx.state, entry, postgame = v.flag(ctx, "FLAG_SYS_GAME_CLEAR"))
        ?: return@Script
    v.set(ctx, v.CONTEST_TYPE, entry.rank + 1) // CONTEST_TYPE_NPC_NORMAL + rank
    ctx.varSet("VAR_CONTEST_HALL_STATE", 1)
    v.setFlag(ctx, "FLAG_ENTERED_CONTEST")
    // The per-category halls share ContestHall's objects and scripts (shared_events_map); the
    // shared map itself is the hall here.
    val hall = ctx.maps?.getMapByName(ctx.state.regionId, "ContestHall") ?: return@Script
    ctx.warp(
        ctx.state.regionId,
        hall.bankId.toInt() and 0xFF,
        hall.mapId.toInt() and 0xFF,
        HALL_X,
        HALL_Y,
        de.fiereu.openmmo.common.enums.Direction.UP)
  }

  /**
   * The hall paintings above the lobby (LilycoveCity_ContestLobby_EventScript_ContestWinner1-6).
   */
  private fun hallWinner(n: Int) = Script { ctx ->
    v.set(ctx, v.V8004, n)
    special(ctx, "ShowContestPainting")
  }

  // ---------------------------------------------------------------- berry trees

  /** BerryTreeScript (data/scripts/berry_tree.inc). */
  private val berryTree = Script { ctx ->
    special(ctx, "ObjectEventInteractionGetBerryTreeData")
    when (v.get(ctx, v.V8004)) {
      BerryTree.STAGE_SPARKLING -> ctx.sign(BerryTreeText.ExclamationPoint)
      BerryTree.STAGE_NO_BERRY -> {
        if (special(ctx, "PlayerHasBerries") == 0) {
          ctx.say(BerryTreeText.ItsSoftLoamySoil)
          return@Script
        }
        if (!ctx.askYesNo(BerryTreeText.WantToPlant)) return@Script
        special(ctx, "Bag_ChooseBerry")
        val item = v.get(ctx, v.ITEM_ID)
        if (item == 0) return@Script
        if (!lane.trees.changeItem(ctx.session, lane.charId(ctx) ?: return@Script, item, -1))
            return@Script
        plantBerry(ctx)
      }
      BerryTree.STAGE_PLANTED -> {
        special(ctx, "ObjectEventInteractionGetBerryCountString")
        ctx.say(BerryTreeText.BerryGrowthStage1)
        wantToWater(ctx)
      }
      BerryTree.STAGE_SPROUTED -> {
        special(ctx, "ObjectEventInteractionGetBerryName")
        ctx.say(BerryTreeText.BerryGrowthStage2)
        wantToWater(ctx)
      }
      BerryTree.STAGE_TALLER -> {
        special(ctx, "ObjectEventInteractionGetBerryName")
        ctx.say(BerryTreeText.BerryGrowthStage3)
        wantToWater(ctx)
      }
      BerryTree.STAGE_FLOWERING -> {
        val adverb =
            when (v.get(ctx, v.V8005)) {
              0 -> "cutely"
              4 -> "very beautifully"
              else -> "prettily"
            }
        ctx.bufferString(2, adverb)
        special(ctx, "ObjectEventInteractionGetBerryName")
        ctx.say(BerryTreeText.BerryGrowthStage4)
        wantToWater(ctx)
      }
      BerryTree.STAGE_BERRIES -> {
        ctx.bufferString(2, v.get(ctx, v.V8006).toString())
        special(ctx, "ObjectEventInteractionGetBerryCountString")
        if (!ctx.askYesNo(BerryTreeText.WantToPick)) {
          ctx.say(BerryTreeText.BerryLeftUnpicked)
          return@Script
        }
        special(ctx, "ObjectEventInteractionPickBerryTree")
        if (v.get(ctx, v.V8004) == 0) {
          ctx.say(BerryTreeText.BerryPocketFull)
          return@Script
        }
        special(ctx, "IncrementDailyPickedBerries")
        special(ctx, "ObjectEventInteractionRemoveBerryTree")
        ctx.say(BerryTreeText.PickedTheBerry)
        ctx.say(BerryTreeText.PutAwayBerry)
      }
    }
  }

  private suspend fun plantBerry(ctx: ScriptContext) {
    special(ctx, "ObjectEventInteractionPlantBerryTree")
    special(ctx, "IncrementDailyPlantedBerries")
    special(ctx, "ObjectEventInteractionGetBerryCountString")
    // "{PLAYER} planted one {STR_VAR_1}": one berry
    val t = lane.treeId(ctx)?.let { lane.trees.tree(lane.charId(ctx) ?: 0, it) }
    if (t != null && t.berry != 0) ctx.bufferString(1, "${lane.data.berry(t.berry).name} BERRY")
    ctx.say(BerryTreeText.PlantedOneBerry)
  }

  /** BerryTree_EventScript_WantToWater (checkitem ITEM_WAILMER_PAIL). */
  private suspend fun wantToWater(ctx: ScriptContext) {
    val charId = lane.charId(ctx) ?: return
    if (!lane.trees.hasItem(charId, "WAILMER_PAIL")) return
    special(ctx, "ObjectEventInteractionGetBerryName")
    if (!ctx.askYesNo(BerryTreeText.WantToWater)) return
    waterBerry(ctx)
  }

  private suspend fun waterBerry(ctx: ScriptContext) {
    special(ctx, "ObjectEventInteractionGetBerryName")
    ctx.say(BerryTreeText.WateredTheBerry)
    special(ctx, "ObjectEventInteractionWaterBerryTree")
    special(ctx, "DoWateringBerryTreeAnim")
    ctx.say(BerryTreeText.PlantIsDelighted)
  }

  /** BerryTree_EventScript_ItemUseWailmerPail: the pail used from the bag at a tree. */
  val itemUseWailmerPail = Script { ctx ->
    special(ctx, "ObjectEventInteractionGetBerryTreeData")
    if (v.get(ctx, v.V8004) in BerryTree.STAGE_PLANTED..BerryTree.STAGE_FLOWERING) waterBerry(ctx)
  }

  // ---------------------------------------------------------------- blenders

  /**
   * BerryBlender_EventScript_BerryBlender1/2/3: [npcs] NPC opponents; blender 1 is the old-timer's
   * (or the Blend Master's when he is in the lobby).
   */
  private fun blender(npcs: Int) = Script { ctx ->
    val charId = lane.charId(ctx) ?: return@Script
    val (ask, decline) =
        when (npcs) {
          1 -> BerryBlender.WantToMakePokeblocks to BerryBlender.MadeOldTimerSad
          2 -> BerryBlender.WantToBlendPokeblocksWithUs to BerryBlender.LeftUsInShock
          else -> BerryBlender.LookGoodAtBlendingJoinUs to BerryBlender.LeftUsInShock
        }
    if (!ctx.askYesNo(ask)) {
      ctx.say(decline)
      return@Script
    }
    if (!lane.trees.hasItem(charId, "POKEBLOCK_CASE")) {
      ctx.say(
          when (npcs) {
            1 -> BerryBlender.DontHavePokeblockCase
            2 -> BerryBlender.DontHavePokeblockCase2
            else -> BerryBlender.DontHavePokeblockCase3
          })
      return@Script
    }
    if (special(ctx, "GetFirstFreePokeblockSlot") == 0xFFFF) {
      ctx.say(
          when (npcs) {
            1 -> BerryBlender.PokeblockCaseIsFull
            2 -> BerryBlender.PokeblockCaseIsFull2
            else -> BerryBlender.PokeblockCaseIsFull3
          })
      return@Script
    }
    if (special(ctx, "PlayerHasBerries") == 0) {
      if (npcs == 1) {
        ctx.say(BerryBlender.DontHaveAnyBerries)
        // the old-timer's spare berry, once a day
        if (v.flag(ctx, FLAG_DAILY_CONTEST_LOBBY_RECEIVED_BERRY)) {
          ctx.say(BerryBlender.DontHaveAnyBerriesToSpare)
          return@Script
        }
        ctx.say(BerryBlender.CanHaveOneOfMyBerries)
        lane.item("PECHA_BERRY")?.let { ctx.giveItem(it) }
        v.setFlag(ctx, FLAG_DAILY_CONTEST_LOBBY_RECEIVED_BERRY)
      } else {
        ctx.say(
            if (npcs == 2) BerryBlender.DontHaveAnyBerries2 else BerryBlender.DontHaveAnyBerries3)
        return@Script
      }
    } else if (npcs == 1) ctx.say(BerryBlender.Excellent)
    val (know, explain, start) =
        when (npcs) {
          1 ->
              Triple(
                  BerryBlender.KnowHowToMakePokeblocks,
                  BerryBlender.ExplainBerryBlending,
                  BerryBlender.LetsBerryBlender)
          2 ->
              Triple(
                  BerryBlender.KnowHowToMakePokeblocks2,
                  BerryBlender.ExplainBerryBlending2,
                  BerryBlender.LetsBerryBlender2)
          else ->
              Triple(
                  BerryBlender.KnowHowToMakePokeblocks3,
                  BerryBlender.ExplainBerryBlending3,
                  BerryBlender.LetsBerryBlender3)
        }
    if (!ctx.askYesNo(know)) ctx.say(explain)
    ctx.say(start)
    v.set(ctx, v.V8004, npcs)
    special(ctx, "DoBerryBlending")
  }

  /** BerryBlender_EventScript_BerryBlenderLink: the blender for players only. */
  private val linkBlender = Script { ctx ->
    val charId = lane.charId(ctx) ?: return@Script
    if (special(ctx, "PlayerHasBerries") == 0) {
      ctx.sign(BerryBlender.NoBerriesLink)
      return@Script
    }
    if (!lane.trees.hasItem(charId, "POKEBLOCK_CASE")) {
      ctx.sign(BerryBlender.DontHavePokeblockCaseLink)
      return@Script
    }
    if (special(ctx, "GetFirstFreePokeblockSlot") == 0xFFFF) {
      ctx.sign(BerryBlender.PokeblockCaseIsFullLink)
      return@Script
    }
    v.set(ctx, v.V8004, 0)
    special(ctx, "DoBerryBlending")
  }

  private val blenderRecords = Script { ctx -> special(ctx, "ShowBerryBlenderRecordWindow") }

  // ---------------------------------------------------------------- museum

  /** LilycoveCity_LilycoveMuseum_2F_EventScript_<Category>Painting. */
  private fun museumPainting(category: Int) = Script { ctx ->
    if (!v.flag(ctx, PAINTING_FLAGS[category])) {
      ctx.sign(
          when (category) {
            Contest.COOL -> Museum2F.ItsRedPictureFrame
            Contest.BEAUTY -> Museum2F.ItsBluePictureFrame
            Contest.CUTE -> Museum2F.ItsPinkPictureFrame
            Contest.SMART -> Museum2F.ItsGreenPictureFrame
            else -> Museum2F.ItsYellowPictureFrame
          })
      return@Script
    }
    ctx.sign(Museum2F.ItsPaintingOfPokemon)
    v.set(ctx, v.V8004, ContestService.CONTEST_WINNER_MUSEUM_COOL + category)
    special(ctx, "ShowContestPainting")
  }

  /**
   * r5 merge: only the receptionists and the link blender stay hand-ported (the entry screen,
   * matchmaking with other online players and the live contest screen are UI flows the decomp's
   * hall cutscene and `startcontest` cannot express). Berry trees, the blenders, the blender
   * records, the hall winners and the museum frames now run the decomp scripts in the interpreter,
   * which calls the same [ContestSpecials]. The other hand ports below are kept for the dev command
   * / tests.
   */
  val byLabel: Map<String, Script> = buildMap {
    put("LilycoveCity_ContestLobby_EventScript_ContestReceptionist", receptionist)
    put("LilycoveCity_ContestLobby_EventScript_LinkContestReceptionist", receptionist)
    // The link blender: two online players blend together (the decomp's needs a link cable).
    put("BerryBlender_EventScript_BerryBlenderLink", linkBlender)
  }

  /** The hand ports the interpreter replaced (r5 merge), still reachable for comparison. */
  val replaced: Map<String, Script> = buildMap {
    for (n in 1..6) put("LilycoveCity_ContestLobby_EventScript_ContestWinner$n", hallWinner(n))
    put("LilycoveCity_ContestLobby_EventScript_BerryBlenderSpeedRecords", blenderRecords)
    put("BerryTreeScript", berryTree)
    put("BerryTree_EventScript_ItemUseWailmerPail", itemUseWailmerPail)
    put("BerryBlender_EventScript_BerryBlender1", blender(1))
    put("BerryBlender_EventScript_BerryBlender2", blender(2))
    put("BerryBlender_EventScript_BerryBlender3", blender(3))
    listOf("Cool", "Beauty", "Cute", "Smart", "Tough").forEachIndexed { i, c ->
      put("LilycoveCity_LilycoveMuseum_2F_EventScript_${c}Painting", museumPainting(i))
    }
  }

  companion object {
    /** setwarp MAP_CONTEST_HALL_*, 7, 5. */
    const val HALL_X = 7
    const val HALL_Y = 5

    const val FLAG_RECEIVED_POKEBLOCK_CASE = "FLAG_RECEIVED_POKEBLOCK_CASE"
    const val FLAG_DAILY_CONTEST_LOBBY_RECEIVED_BERRY = "FLAG_DAILY_CONTEST_LOBBY_RECEIVED_BERRY"
    val PAINTING_FLAGS =
        listOf(
            "FLAG_COOL_PAINTING_MADE",
            "FLAG_BEAUTY_PAINTING_MADE",
            "FLAG_CUTE_PAINTING_MADE",
            "FLAG_SMART_PAINTING_MADE",
            "FLAG_TOUGH_PAINTING_MADE")
  }
}
