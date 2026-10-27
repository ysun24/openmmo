package de.fiereu.openmmo.server.game.contest

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** gContestMoves[move]: the contest side of a move (src/data/contest_moves.h). */
data class ContestMoveDef(
    val effect: Int,
    val category: Int,
    /** COMBO_STARTER_* id this move starts, 0 for none. */
    val comboStarter: Int,
    /** The COMBO_STARTER_* ids this move finishes. */
    val comboMoves: List<Int>,
)

/** gContestEffects[effect]. */
data class ContestEffectDef(val type: Int, val appeal: Int, val jam: Int)

/** One of gContestOpponents (src/data/contest_opponents.h). */
data class ContestOpponentDef(
    val key: String,
    val dex: Int,
    val nickname: String,
    val trainer: String,
    val gfx: String,
    val aiFlags: Long,
    val rank: Int,
    /** aiPool_Cool .. aiPool_Tough. */
    val pools: List<Boolean>,
    val moves: List<Int>,
    /** cool, beauty, cute, smart, tough, sheen. */
    val stats: List<Int>,
    /** CONTEST_FILTER_NONE / NO_POSTGAME / ONLY_POSTGAME. */
    val filter: Int,
)

/** One assembled contest AI instruction: opcode, byte arguments, jump target (-1 for none). */
data class AiInstruction(val op: Int, val args: List<Int>, val target: Int)

/** gBerries (src/berry.c), the index is the berry type - 1. */
data class BerryDef(
    val type: Int,
    val item: String,
    val name: String,
    val firmness: Int,
    val size: Int,
    val maxYield: Int,
    val minYield: Int,
    val description: List<String>,
    val stageHours: Int,
    /** spicy, dry, sweet, bitter, sour. */
    val flavors: List<Int>,
    val smoothness: Int,
)

/** A berry tree object on a map (BerryTreeScript with trainer_sight_or_berry_tree_id). */
data class BerryTreeSpot(val map: String, val x: Int, val y: Int, val localId: Int, val tree: Int)

/** setberrytree in data/scripts/new_game.inc. */
data class InitialBerryTree(val tree: Int, val item: String, val stage: Int)

/**
 * Every decomp table of round 5's contest lane, loaded once from the resource that
 * codegen/tools/gen_contest_data.py writes.
 */
class ContestData
private constructor(root: JsonObject) {
  val moves: Map<Int, ContestMoveDef>
  val effects: List<ContestEffectDef>
  val comboStarterLookup: List<Boolean>
  /** sContestExcitementTable[contest category][move category]. */
  val excitement: List<List<Int>>
  val opponents: List<ContestOpponentDef>
  val aiProgram: List<AiInstruction>
  /** gContestAI_ScriptsTable: the instruction index of each AI flag's script. */
  val aiTable: List<Int>
  val effectDescriptions: List<String>
  val appealResults: List<String>
  val roundResults: List<String>
  val conditionNames: List<String>
  val misc: Map<String, String>
  val berries: List<BerryDef>
  val berryTrees: List<BerryTreeSpot>
  val initialBerryTrees: List<InitialBerryTree>
  /** gPokeblockFlavorCompatibilityTable, per nature the five flavours (-1, 0, +1). */
  val flavorCompatibility: List<List<Int>>
  val pokeblockNames: List<String>
  val blenderOpponentBerrySets: List<List<Int>>
  val berryMasterBerries: List<Int>
  val blackFlavorFlags: List<Int>
  val arrowHitRangeStart: List<Int>
  val speedDivisor: List<Int>
  val blenderNames: Map<String, String>

  init {
    fun JsonElement.ints() = jsonArray.map { it.jsonPrimitive.int }
    fun JsonElement.strs() = jsonArray.map { it.jsonPrimitive.content }
    fun JsonObject.i(k: String) = getValue(k).jsonPrimitive.int
    fun JsonObject.s(k: String) = getValue(k).jsonPrimitive.content
    moves =
        root.getValue("moves").jsonObject.entries.associate { (k, v) ->
          val o = v.jsonObject
          k.toInt() to
              ContestMoveDef(o.i("effect"), o.i("category"), o.i("starter"), o.getValue("combos").ints())
        }
    effects =
        root.getValue("effects").jsonArray.map {
          val o = it.jsonObject
          ContestEffectDef(o.i("type"), o.i("appeal"), o.i("jam"))
        }
    comboStarterLookup = root.getValue("comboStarterLookup").ints().map { it != 0 }
    excitement = root.getValue("excitement").jsonArray.map { it.ints() }
    opponents =
        root.getValue("opponents").jsonArray.map {
          val o = it.jsonObject
          ContestOpponentDef(
              o.s("key"),
              o.i("dex"),
              o.s("nickname"),
              o.s("trainer"),
              o.s("gfx"),
              o.getValue("ai").jsonPrimitive.long,
              o.i("rank"),
              o.getValue("pools").ints().map { p -> p != 0 },
              o.getValue("moves").ints(),
              o.getValue("stats").ints(),
              o.i("filter"),
          )
        }
    val ai = root.getValue("ai").jsonObject
    aiProgram =
        ai.getValue("program").jsonArray.map {
          val a = it.jsonArray
          AiInstruction(a[0].jsonPrimitive.int, a[1].ints(), a[2].jsonPrimitive.int)
        }
    aiTable = ai.getValue("table").ints()
    val texts = root.getValue("texts").jsonObject
    effectDescriptions = texts.getValue("effectDescriptions").strs()
    appealResults = texts.getValue("appealResults").strs()
    roundResults = texts.getValue("roundResults").strs()
    conditionNames = texts.getValue("conditions").strs()
    misc = texts.getValue("misc").jsonObject.mapValues { it.value.jsonPrimitive.content }
    berries =
        root.getValue("berries").jsonArray.mapIndexed { i, it ->
          val o = it.jsonObject
          BerryDef(
              i + 1,
              o.s("item"),
              o.s("name"),
              o.i("firmness"),
              o.i("size"),
              o.i("maxYield"),
              o.i("minYield"),
              o.getValue("description").strs(),
              o.i("stageHours"),
              o.getValue("flavors").ints(),
              o.i("smoothness"),
          )
        }
    berryTrees =
        root.getValue("berryTrees").jsonArray.map {
          val o = it.jsonObject
          BerryTreeSpot(o.s("map"), o.i("x"), o.i("y"), o.i("localId"), o.i("tree"))
        }
    initialBerryTrees =
        root.getValue("initialBerryTrees").jsonArray.map {
          val o = it.jsonObject
          InitialBerryTree(o.i("tree"), o.s("item"), o.i("stage"))
        }
    val pb = root.getValue("pokeblock").jsonObject
    flavorCompatibility = pb.getValue("compatibility").jsonArray.map { it.ints() }
    pokeblockNames = pb.getValue("names").strs()
    val bl = root.getValue("blender").jsonObject
    blenderOpponentBerrySets = bl.getValue("opponentBerrySets").jsonArray.map { it.ints() }
    berryMasterBerries = bl.getValue("berryMasterBerries").ints()
    blackFlavorFlags = bl.getValue("blackFlavorFlags").ints()
    arrowHitRangeStart = bl.getValue("arrowHitRangeStart").ints()
    speedDivisor = bl.getValue("speedDivisor").ints()
    blenderNames = bl.getValue("names").jsonObject.mapValues { it.value.jsonPrimitive.content }
  }

  fun move(id: Int): ContestMoveDef = moves[id] ?: NO_MOVE

  fun effectOf(move: Int): ContestEffectDef = effects[move(move).effect]

  /** GetBerryInfo: type 1..43, out-of-range falls back to Cheri like the decomp. */
  fun berry(type: Int): BerryDef =
      if (type in 1..berries.size) berries[type - 1] else berries[0]

  fun berryByItem(item: String): BerryDef? = berries.firstOrNull { it.item == item }

  companion object {
    private val NO_MOVE = ContestMoveDef(0, 0, 0, emptyList())

    val instance: ContestData by lazy {
      val text =
          checkNotNull(ContestData::class.java.getResourceAsStream("/contest/contest_data.json")) {
                "contest/contest_data.json is missing; run codegen/tools/gen_contest_data.py"
              }
              .use { it.readBytes().toString(Charsets.UTF_8) }
      ContestData(Json.parseToJsonElement(text).jsonObject)
    }

    @Suppress("unused") private fun JsonArray.ignore() = Unit
  }
}

/** The decomp's contest constants. */
object Contest {
  const val COUNT = 4
  const val NUM_APPEALS = 5
  const val LAST_APPEAL = NUM_APPEALS - 1
  const val NONE = 0xFF

  const val COOL = 0
  const val BEAUTY = 1
  const val CUTE = 2
  const val SMART = 3
  const val TOUGH = 4
  val CATEGORY_NAMES = listOf("COOL", "BEAUTY", "CUTE", "SMART", "TOUGH")

  const val RANK_NORMAL = 0
  const val RANK_SUPER = 1
  const val RANK_HYPER = 2
  const val RANK_MASTER = 3
  val RANK_NAMES = listOf("NORMAL", "SUPER", "HYPER", "MASTER")

  // CONDITION_* (conditionMod)
  const val CONDITION_NO_CHANGE = 0
  const val CONDITION_GAIN = 1
  const val CONDITION_LOSE = 2

  // TryEnterContestMon results
  const val CANT_ENTER = 0
  const val CAN_ENTER_EQUAL_RANK = 1
  const val CAN_ENTER_HIGH_RANK = 2
  const val CANT_ENTER_EGG = 3
  const val CANT_ENTER_FAINTED = 4

  // CONTEST_FILTER_*
  const val FILTER_NO_POSTGAME = 1
  const val FILTER_ONLY_POSTGAME = 2
}

/** CONTEST_STRING_* (include/contest.h). */
object ContestString {
  const val MORE_CONSCIOUS = 0
  const val NO_APPEAL = 1
  const val SETTLE_DOWN = 2
  const val OBLIVIOUS_TO_OTHERS = 3
  const val LESS_AWARE = 4
  const val STOPPED_CARING = 5
  const val STARTLE_ATTEMPT = 6
  const val DAZZLE_ATTEMPT = 7
  const val JUDGE_LOOK_AWAY2 = 8
  const val UNNERVE_ATTEMPT = 9
  const val NERVOUS = 10
  const val UNNERVE_WAITING = 11
  const val TAUNT_WELL = 12
  const val REGAINED_FORM = 13
  const val JAM_WELL = 14
  const val HUSTLE_STANDOUT = 15
  const val WORK_HARD_UNNOTICED = 16
  const val WORK_BEFORE = 17
  const val APPEAL_NOT_WELL = 18
  const val WORK_PRECEDING = 19
  const val APPEAL_NOT_WELL2 = 20
  const val APPEAL_NOT_SHOWN_WELL = 21
  const val APPEAL_SLIGHTLY_WELL = 22
  const val APPEAL_PRETTY_WELL = 23
  const val APPEAL_EXCELLENTLY = 24
  const val APPEAL_DUD = 25
  const val APPEAL_NOT_VERY_WELL = 26
  const val APPEAL_SLIGHTLY_WELL2 = 27
  const val APPEAL_PRETTY_WELL2 = 28
  const val APPEAL_VERY_WELL = 29
  const val APPEAL_EXCELLENTLY2 = 30
  const val SAME_TYPE_GOOD = 31
  const val DIFF_TYPE_GOOD = 32
  const val STOOD_OUT_AS_MUCH = 33
  const val NOT_AS_WELL = 34
  const val CONDITION_ROSE = 35
  const val HOT_STATUS = 36
  const val MOVE_UP_LINE = 37
  const val MOVE_BACK_LINE = 38
  const val SCRAMBLE_ORDER = 39
  const val JUDGE_EXPECTANTLY2 = 40
  const val WENT_OVER_WELL = 41
  const val WENT_OVER_VERY_WELL = 42
  const val APPEAL_COMBO_EXCELLENTLY = 43
  const val AVERT_GAZE = 44
  const val AVOID_SEEING = 45
  const val NOT_FAZED = 46
  const val LITTLE_DISTRACTED = 47
  const val ATTEMPT_STARTLE = 48
  const val LOOKED_DOWN = 49
  const val TURNED_BACK = 50
  const val UTTER_CRY = 51
  const val LEAPT_UP = 52
  const val TRIPPED_OVER = 53
  const val MESSED_UP2 = 54
  const val FAILED_TARGET_NERVOUS = 55
  const val FAILED_ANYONE_NERVOUS = 56
  const val IGNORED = 57
  const val NO_CONDITION_IMPROVE = 58
  const val BAD_CONDITION_WEAK_APPEAL = 59
  const val UNAFFECTED = 60
  const val ATTRACTED_ATTENTION = 61
  const val NONE = 255
}
