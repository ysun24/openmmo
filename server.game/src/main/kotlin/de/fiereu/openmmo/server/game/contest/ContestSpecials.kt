package de.fiereu.openmmo.server.game.contest

import de.fiereu.openmmo.items.ItemDef
import de.fiereu.openmmo.server.game.battle.ItemNames
import de.fiereu.openmmo.server.game.script.ScriptContext
import de.fiereu.openmmo.server.game.script.ScriptSpecial
import de.fiereu.openmmo.server.game.storage.CharacterStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The decomp's special variables as this lane reads and writes them. They go through
 * [ScriptContext.getVar] / [ScriptContext.setVar] under their decomp names (VAR_0x8004,
 * VAR_CONTEST_CATEGORY, …), the one place to change if the interpreter keeps temporaries elsewhere.
 */
object SpecialVars {
  const val V8004 = "VAR_0x8004"
  const val V8005 = "VAR_0x8005"
  const val V8006 = "VAR_0x8006"
  const val RESULT = "VAR_RESULT"
  const val ITEM_ID = "VAR_ITEM_ID"
  const val CONTEST_CATEGORY = "VAR_CONTEST_CATEGORY"
  const val CONTEST_RANK = "VAR_CONTEST_RANK"
  const val CONTEST_TYPE = "VAR_CONTEST_TYPE"

  /** Story keys are namespaced by region ("hoenn/VAR_RESULT", "kanto/FLAG_..."). */
  fun key(ctx: ScriptContext, name: String): String =
      (if (ctx.state.regionId == 0) "kanto/" else "hoenn/") + name

  // r5 merge: one accessor for the temp / special vars (ScriptContext.varGet / varSet).
  fun get(ctx: ScriptContext, name: String): Int = ctx.varGet(name)

  fun set(ctx: ScriptContext, name: String, v: Int) = ctx.varSet(name, v)

  fun flag(ctx: ScriptContext, name: String): Boolean = ctx.isFlagSet(key(ctx, name))

  fun setFlag(ctx: ScriptContext, name: String) = ctx.setFlag(key(ctx, name))
}

/** Every service of the lane, for the specials and the lane's scripts. */
@Singleton
class ContestLane
@Inject
constructor(
    val contests: ContestService,
    val stats: ContestStatsService,
    val pokeblocks: PokeblockService,
    val trees: BerryTreeService,
    val blender: BlenderService,
    val docs: ContestDocs,
    val characters: CharacterStore,
    val names: ContestNames,
) {
  val data = ContestData.instance

  fun item(name: String): ItemDef? = ItemNames.byDecompName(name)

  fun charId(ctx: ScriptContext): Long? = ctx.state.characterId

  fun party(ctx: ScriptContext) =
      charId(ctx)
          ?.let { characters.getCharacter(it)?.pokemon }
          .orEmpty()
          .sortedBy { it.containerSlot }

  /** GetLeadMonIndex: the first party monster that is not an egg. */
  fun leadMon(ctx: ScriptContext) = party(ctx).firstOrNull { !it.isEgg } ?: party(ctx).firstOrNull()

  /** The contest view of the player's last contest. */
  fun view(ctx: ScriptContext) = charId(ctx)?.let { contests.result(it) }

  /** gContestMonPartyIndex. */
  fun contestMon(ctx: ScriptContext) =
      charId(ctx)?.let { contests.entries[it] }?.let { e -> party(ctx).getOrNull(e.slot) }

  fun category(ctx: ScriptContext) = SpecialVars.get(ctx, SpecialVars.CONTEST_CATEGORY)

  fun rank(ctx: ScriptContext) = SpecialVars.get(ctx, SpecialVars.CONTEST_RANK)

  /** The berry tree of the object the player talked to (gSelectedObjectEvent). */
  fun treeId(ctx: ScriptContext): Int? = trees.treeIdForEntity(ctx.state, ctx.entityId)
}

/**
 * The r5-contests specials: contests (contest_util.c), the Pokéblock case and blender, berry trees
 * (berry.c), the museum paintings and ribbons (field_specials.c). Names are the decomp's
 * (data/specials.inc of both games); each returns what the decomp writes to VAR_RESULT.
 */
@Singleton
class ContestSpecials @Inject constructor(private val lane: ContestLane) : ScriptSpecial {
  override val names: Set<String> =
      setOf(
          // contest reception / hall (contest_util.c)
          "TryEnterContestMon",
          "HasMonWonThisContestBefore",
          "GiveMonContestRibbon",
          "GetContestMonConditionRanking",
          "GetContestMonCondition",
          "GetContestWinnerId",
          "BufferContestWinnerTrainerName",
          "BufferContestWinnerMonName",
          "GetContestPlayerId",
          "SetContestTrainerGfxIds",
          "GetNpcContestantLocalId",
          "BufferContestTrainerAndMonNames",
          "DoesContestCategoryHaveMuseumPainting",
          "SaveMuseumContestPainting",
          "ShouldReadyContestArtist",
          "CountPlayerMuseumPaintings",
          "GetContestantNamesAtRank",
          "ShowContestPainting",
          "GiveMonArtistRibbon",
          "IsContestDebugActive",
          "ShowContestEntryMonPic",
          "HideContestEntryMonPic",
          "GetContestMultiplayerId",
          "GenerateContestRand",
          "DoContestHallWarp",
          "SetLinkContestPlayerGfx",
          "LoadLinkContestPlayerPalettes",
          // the link reception: link contests are the online players of the same contest here
          "ClearLinkContestFlags",
          "IsWirelessContest",
          "IsContestWithRSPlayer",
          "LinkContestWaitForConnection",
          "LinkContestTryShowWirelessIndicator",
          "LinkContestTryHideWirelessIndicator",
          "TryContestGModeLinkup",
          "TryContestEModeLinkup",
          "TryContestLinkup",
          // the Lilycove contest lady (contest_lady / lilycove_lady.c)
          "ShouldContestLadyShowGoOnAir",
          "HasPlayerGivenContestLadyPokeblock",
          "Script_BufferContestLadyCategoryAndMonName",
          "OpenPokeblockCaseForContestLady",
          "SetContestLadyGivenPokeblock",
          "GetContestLadyMonSpecies",
          "GetContestLadyCategory",
          "PutLilycoveContestLadyShowOnTheAir",
          // pokeblocks / blender
          "GetFirstFreePokeblockSlot",
          "OpenPokeblockCaseOnFeeder",
          "GetPokeblockFeederInFront",
          "GetPokeblockNameByMonNature",
          "DoBerryBlending",
          "TryBerryBlenderLinkup",
          "ShowBerryBlenderRecordWindow",
          // berry trees (berry.c)
          "ObjectEventInteractionGetBerryTreeData",
          "ObjectEventInteractionGetBerryName",
          "ObjectEventInteractionGetBerryCountString",
          "Bag_ChooseBerry",
          "ObjectEventInteractionPlantBerryTree",
          "ObjectEventInteractionPickBerryTree",
          "ObjectEventInteractionRemoveBerryTree",
          "ObjectEventInteractionWaterBerryTree",
          "DoWateringBerryTreeAnim",
          "PlayerHasBerries",
          "HasAtLeastOneBerry",
          "IncrementDailyPlantedBerries",
          "IncrementDailyPickedBerries",
          // museum paintings
          "GetDewfordHallPaintingNameIndex",
          "OpenMuseumFossilPic",
          "CloseMuseumFossilPic",
          // contest stats and ribbons of the lead monster (field_specials.c)
          "CheckLeadMonCool",
          "CheckLeadMonBeauty",
          "CheckLeadMonCute",
          "CheckLeadMonSmart",
          "CheckLeadMonTough",
          "LeadMonHasEffortRibbon",
          "GiveLeadMonEffortRibbon",
      )

  override suspend fun run(ctx: ScriptContext, name: String): Int {
    val l = lane
    val charId = l.charId(ctx) ?: return 0
    val v = SpecialVars
    return when (name) {
      "TryEnterContestMon" -> {
        // gContestMonPartyIndex is VAR_0x8004 after choosecontestmon
        val slot = v.get(ctx, v.V8004)
        val mon = l.party(ctx).getOrNull(slot) ?: return Contest.CANT_ENTER
        l.contests.entries[charId] = ContestEntry(slot, l.category(ctx), l.rank(ctx))
        l.contests.eligibility(mon, l.category(ctx), l.rank(ctx))
      }
      "HasMonWonThisContestBefore" -> {
        val mon = l.contestMon(ctx) ?: return 0
        if (l.stats.get(mon.id).ribbon(l.category(ctx)) > l.rank(ctx)) 1 else 0
      }
      "GiveMonContestRibbon" -> {
        l.view(ctx)?.let { l.contests.giveContestRibbon(it) }
        0
      }
      "GetContestMonConditionRanking" -> {
        val view = l.view(ctx) ?: return 0
        val who = view.fromView(v.get(ctx, v.V8006))
        val rank =
            (0 until Contest.COUNT).count {
              view.engine.round1Points[who] < view.engine.round1Points[it]
            }
        v.set(ctx, v.V8004, rank)
        0
      }
      "GetContestMonCondition" -> {
        val view = l.view(ctx) ?: return 0
        v.set(ctx, v.V8004, view.engine.round1Points[view.fromView(v.get(ctx, v.V8006))])
        0
      }
      "GetContestWinnerId" -> {
        v.set(ctx, v.V8005, l.view(ctx)?.winnerView ?: 0)
        0
      }
      "BufferContestWinnerTrainerName" -> {
        l.view(ctx)?.let { ctx.bufferString(3, it.engine.mons[it.engine.winner].trainerName) }
        0
      }
      "BufferContestWinnerMonName" -> {
        l.view(ctx)?.let { ctx.bufferString(1, it.engine.mons[it.engine.winner].nickname) }
        0
      }
      "GetContestPlayerId" -> {
        v.set(ctx, v.V8004, 3)
        0
      }
      "SetContestTrainerGfxIds",
      "SetLinkContestPlayerGfx",
      "LoadLinkContestPlayerPalettes",
      "LinkContestTryShowWirelessIndicator",
      "LinkContestTryHideWirelessIndicator",
      "LinkContestWaitForConnection",
      "ClearLinkContestFlags",
      "HideContestEntryMonPic",
      "DoContestHallWarp",
      "DoWateringBerryTreeAnim",
      "IncrementDailyPlantedBerries",
      "IncrementDailyPickedBerries",
      "PutLilycoveContestLadyShowOnTheAir" -> 0
      "CloseMuseumFossilPic" -> {
        l.docs.close(ctx.session, "painting")
        0
      }
      "GetNpcContestantLocalId" -> {
        val c = v.get(ctx, v.V8005)
        v.set(ctx, v.V8004, if (c in 0..2) LOCALID_CONTESTANT_1 + c else 100)
        0
      }
      "BufferContestTrainerAndMonNames" -> {
        val view = l.view(ctx) ?: return 0
        val m = view.engine.mons[view.fromView(v.get(ctx, v.V8006))]
        ctx.bufferString(1, m.trainerName)
        ctx.bufferString(3, m.nickname)
        v.set(ctx, v.V8004, m.dex)
        0
      }
      "DoesContestCategoryHaveMuseumPainting" -> {
        val has = l.contests.paintings(charId).any { it.category == l.category(ctx) }
        v.set(ctx, v.V8004, if (has) 1 else 0)
        0
      }
      "SaveMuseumContestPainting" -> {
        l.view(ctx)?.let { l.contests.saveMuseumPainting(charId, it) }
        0
      }
      "ShouldReadyContestArtist" -> {
        v.set(
            ctx,
            v.V8004,
            if (l.view(ctx)?.let { l.contests.shouldReadyArtist(it) } == true) 1 else 0)
        0
      }
      "CountPlayerMuseumPaintings" ->
          l.contests.paintings(charId).count { it.category < ContestService.HALL_BASE }
      "GetContestantNamesAtRank" -> {
        val view = l.view(ctx) ?: return 0
        val sorted = view.engine.round1Points.sortedDescending()
        val r = v.get(ctx, v.V8006).coerceIn(0, 3)
        val who = (0 until Contest.COUNT).first { view.engine.round1Points[it] == sorted[r] }
        ctx.bufferString(1, view.engine.mons[who].nickname)
        ctx.bufferString(2, view.engine.mons[who].trainerName)
        0
      }
      "ShowContestPainting" -> {
        l.contests.showPainting(ctx.session, charId, v.get(ctx, v.V8004))
        0
      }
      "GiveMonArtistRibbon" ->
          if (l.view(ctx)?.let { l.contests.giveArtistRibbon(it) } == true) 1 else 0
      "IsContestDebugActive",
      "IsWirelessContest",
      "IsContestWithRSPlayer",
      "ShouldContestLadyShowGoOnAir" -> 0
      "ShowContestEntryMonPic" -> 0
      "GetContestMultiplayerId" -> 4 // MAX_LINK_PLAYERS: not a 4-player G-mode link
      "GenerateContestRand" -> {
        val max = v.get(ctx, v.RESULT).coerceAtLeast(1)
        (LiveRng.next() % max)
      }
      // Online players of the same category and rank meet in the contest's join window, so a
      // linkup always succeeds (LINKUP_SUCCESS).
      "TryContestGModeLinkup",
      "TryContestEModeLinkup",
      "TryContestLinkup",
      "TryBerryBlenderLinkup" -> 1
      "HasPlayerGivenContestLadyPokeblock" -> if (SpecialVars.flag(ctx, LADY_FLAG)) 1 else 0
      "Script_BufferContestLadyCategoryAndMonName" -> {
        val cat = SpecialVars.get(ctx, LADY_CATEGORY_VAR)
        ctx.bufferString(2, LADY_CATEGORY_NAMES.getOrElse(cat) { "" })
        ctx.bufferString(1, LADY_MONS.getOrElse(cat) { LADY_MONS[0] }.second)
        0
      }
      "OpenPokeblockCaseForContestLady" -> {
        // the case opens to pick one block (VAR_RESULT = the slot, 0xFFFF for none)
        l.pokeblocks.sendCase(
            ctx.session, charId, open = true, message = "Choose a POKéBLOCK to give.")
        val a = l.docs.await(ctx.session, "pokeblocks")
        l.docs.close(ctx.session, "pokeblocks")
        val slot = a?.takeIf { it.str("t") == "pokeblocks.give" }?.int("slot")
        if (slot != null && l.pokeblocks.toss(charId, slot)) {
          v.set(ctx, v.V8004, slot)
          slot
        } else 0xFFFF
      }
      "SetContestLadyGivenPokeblock" -> {
        SpecialVars.setFlag(ctx, LADY_FLAG)
        0
      }
      "GetContestLadyMonSpecies" -> {
        v.set(
            ctx,
            v.V8005,
            LADY_MONS.getOrElse(SpecialVars.get(ctx, LADY_CATEGORY_VAR)) { LADY_MONS[0] }.first)
        0
      }
      "GetContestLadyCategory" -> {
        v.set(ctx, v.V8004, SpecialVars.get(ctx, LADY_CATEGORY_VAR))
        SpecialVars.get(ctx, LADY_CATEGORY_VAR)
      }
      "GetFirstFreePokeblockSlot" ->
          l.pokeblocks.firstFreeSlot(charId).let { if (it < 0) 0xFFFF else it }
      "OpenPokeblockCaseOnFeeder" -> {
        l.pokeblocks.sendCase(ctx.session, charId, open = true)
        l.docs.await(ctx.session, "pokeblocks")
        l.docs.close(ctx.session, "pokeblocks")
        0
      }
      "GetPokeblockFeederInFront" -> {
        v.set(ctx, v.V8004, 0xFFFF)
        0
      }
      "GetPokeblockNameByMonNature" -> {
        val lead = l.leadMon(ctx) ?: return 0
        val n = PokeblockRules.favoriteName(lead.nature) ?: return 0
        ctx.bufferString(1, n)
        1
      }
      "DoBerryBlending" -> {
        val npc = v.get(ctx, v.V8004).coerceIn(0, 3)
        val master =
            npc == 1 && !SpecialVars.flag(ctx, "FLAG_HIDE_LILYCOVE_CONTEST_HALL_BLEND_MASTER")
        l.blender.blend(ctx.session, ctx.state, npc, master)
        0
      }
      "ShowBerryBlenderRecordWindow" -> {
        l.blender.showRecord(ctx.session, charId)
        0
      }
      "ObjectEventInteractionGetBerryTreeData" -> {
        val id = l.treeId(ctx) ?: return 0
        // AllowBerryTreeGrowth
        val t =
            l.trees.update(charId, id) { it.copy(stopGrowth = false) } ?: l.trees.tree(charId, id)
        v.set(ctx, v.V8004, t.stage)
        v.set(ctx, v.V8005, t.wateredCount)
        v.set(ctx, v.V8006, t.berryYield)
        ctx.bufferString(1, berryCountString(t))
        0
      }
      "ObjectEventInteractionGetBerryName" -> {
        val t = l.treeId(ctx)?.let { l.trees.tree(charId, it) } ?: return 0
        ctx.bufferString(1, l.data.berry(t.berry).name)
        0
      }
      "ObjectEventInteractionGetBerryCountString" -> {
        val t = l.treeId(ctx)?.let { l.trees.tree(charId, it) } ?: return 0
        ctx.bufferString(1, berryCountString(t))
        0
      }
      "Bag_ChooseBerry" -> {
        val item = chooseBerry(ctx)
        v.set(ctx, v.ITEM_ID, item)
        0
      }
      "ObjectEventInteractionPlantBerryTree" -> {
        val id = l.treeId(ctx) ?: return 0
        val berry = itemToBerry(v.get(ctx, v.ITEM_ID))
        l.trees.update(charId, id) {
          BerryTreeRules.plant(berry, BerryTree.STAGE_PLANTED, true, LiveRng)
        }
        refreshTrees(ctx)
        run(ctx, "ObjectEventInteractionGetBerryTreeData")
      }
      "ObjectEventInteractionPickBerryTree" -> {
        val id = l.treeId(ctx) ?: return 0
        val t = l.trees.tree(charId, id)
        val itemId = l.trees.itemIdOf(l.data.berry(t.berry)) ?: return 0
        val ok = l.trees.changeItem(ctx.session, charId, itemId, t.berryYield)
        v.set(ctx, v.V8004, if (ok) 1 else 0)
        0
      }
      "ObjectEventInteractionRemoveBerryTree" -> {
        val id = l.treeId(ctx) ?: return 0
        l.trees.update(charId, id) { BerryTree.BLANK }
        refreshTrees(ctx)
        0
      }
      "ObjectEventInteractionWaterBerryTree" -> {
        val id = l.treeId(ctx) ?: return 0
        val ok = l.trees.update(charId, id) { BerryTreeRules.water(it) } != null
        refreshTrees(ctx)
        if (ok) 1 else 0
      }
      "PlayerHasBerries",
      "HasAtLeastOneBerry" -> if (l.trees.berriesInBag(charId).isNotEmpty()) 1 else 0
      "GetDewfordHallPaintingNameIndex" -> 0
      "OpenMuseumFossilPic" -> {
        // Pewter museum (FRLG): VAR_0x8004 is the species (Kanto species ids are national dex
        // numbers); the picture stays up while the message box reads, CloseMuseumFossilPic ends it.
        val dex = v.get(ctx, v.V8004)
        l.docs.send(ctx.session, "painting") {
          put("open", true)
          put("dex", dex)
          put("fossil", true)
          put("caption", "")
        }
        0
      }
      "CheckLeadMonCool" -> leadStat(ctx) { it.cool }
      "CheckLeadMonBeauty" -> leadStat(ctx) { it.beauty }
      "CheckLeadMonCute" -> leadStat(ctx) { it.cute }
      "CheckLeadMonSmart" -> leadStat(ctx) { it.smart }
      "CheckLeadMonTough" -> leadStat(ctx) { it.tough }
      "LeadMonHasEffortRibbon" -> {
        val lead = l.leadMon(ctx) ?: return 0
        if (l.stats.get(lead.id).has(Ribbon.EFFORT)) 1 else 0
      }
      "GiveLeadMonEffortRibbon" -> {
        val lead = l.leadMon(ctx) ?: return 0
        val c = l.stats.get(lead.id)
        l.stats.set(lead.id, c.copy(ribbons = c.ribbons or Ribbon.EFFORT))
        SpecialVars.setFlag(ctx, "FLAG_SYS_RIBBON_GET")
        0
      }
      else -> 0
    }
  }

  private fun kotlinx.serialization.json.JsonObjectBuilder.put(k: String, v: Boolean) =
      put(k, kotlinx.serialization.json.JsonPrimitive(v))

  private fun kotlinx.serialization.json.JsonObjectBuilder.put(k: String, v: Int) =
      put(k, kotlinx.serialization.json.JsonPrimitive(v))

  private fun kotlinx.serialization.json.JsonObjectBuilder.put(k: String, v: String) =
      put(k, kotlinx.serialization.json.JsonPrimitive(v))

  /** CheckLeadMon*: the lead monster's stat is at least 200. */
  private suspend fun leadStat(ctx: ScriptContext, stat: (MonContest) -> Int): Int {
    val lead = lane.leadMon(ctx) ?: return 0
    return if (stat(lane.stats.get(lead.id)) >= 200) 1 else 0
  }

  /** GetBerryCountString: "CHERI BERRY" or "CHERI BERRIES". */
  private fun berryCountString(t: BerryTree): String {
    if (t.berry == 0) return ""
    val n = lane.data.berry(t.berry).name
    return if (t.berryYield < 2) "$n BERRY" else "$n BERRIES"
  }

  private fun itemToBerry(itemWireId: Int): Int {
    val b = lane.data.berries.firstOrNull { lane.trees.itemIdOf(it) == itemWireId }
    return b?.type ?: 1
  }

  /** CB2_ChooseBerry: the bag's berries pocket; the wire item id, or 0 for cancel. */
  private suspend fun chooseBerry(ctx: ScriptContext): Int {
    val charId = lane.charId(ctx) ?: return 0
    val list = lane.trees.berriesInBag(charId)
    if (list.isEmpty()) return 0
    lane.docs.send(ctx.session, "berryChoose") {
      put("open", true)
      put(
          "berries",
          kotlinx.serialization.json.buildJsonArray {
            for ((b, id, q) in list) add(
                kotlinx.serialization.json.buildJsonObject {
                  put("item", id)
                  put("berry", b.type)
                  put("name", b.name)
                  put("qty", q)
                  put("description", b.description.joinToString(" "))
                })
          })
    }
    val a = lane.docs.await(ctx.session, "berryChoose")
    lane.docs.close(ctx.session, "berryChoose")
    val item = a?.takeIf { it.str("t") == "berryChoose.pick" }?.int("item") ?: return 0
    return if (list.any { it.second == item }) item else 0
  }

  private suspend fun refreshTrees(ctx: ScriptContext) =
      lane.trees.sendMapTrees(ctx.session, ctx.state)

  companion object {
    const val LOCALID_CONTESTANT_1 = 3
    /** lilycoveLady.contest.givenPokeblock / .category, kept as story state. */
    const val LADY_FLAG = "R5C_CONTEST_LADY_GIVEN_POKEBLOCK"
    const val LADY_CATEGORY_VAR = "R5C_CONTEST_LADY_CATEGORY"
    /**
     * The contest lady's monster per category (src/data/lilycove_lady.h sContestLadyMonSpecies as
     * national dex numbers, sContestLadyMonNames).
     */
    val LADY_MONS =
        listOf(
            263 to "HANDSOME", // ZIGZAGOON
            300 to "VINNY", // SKITTY
            261 to "MOREME", // POOCHYENA
            352 to "IRONHARD", // KECLEON
            25 to "MUSCLE") // PIKACHU
    val LADY_CATEGORY_NAMES = listOf("coolness", "beauty", "cuteness", "smartness", "toughness")
  }
}
