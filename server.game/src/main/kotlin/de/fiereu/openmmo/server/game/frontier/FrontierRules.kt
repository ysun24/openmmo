package de.fiereu.openmmo.server.game.frontier

import kotlin.random.Random

/** FRONTIER_FACILITY_* in decomp order (the ordinal is the decomp's value). */
enum class Facility(val key: String, val title: String) {
  TOWER("tower", "BATTLE TOWER"),
  DOME("dome", "BATTLE DOME"),
  PALACE("palace", "BATTLE PALACE"),
  ARENA("arena", "BATTLE ARENA"),
  FACTORY("factory", "BATTLE FACTORY"),
  PIKE("pike", "BATTLE PIKE"),
  PYRAMID("pyramid", "BATTLE PYRAMID");

  companion object {
    fun byKey(key: String): Facility? = entries.firstOrNull { it.key == key }
  }
}

/** FRONTIER_LVL_*: Level 50, Open Level, and the Battle Tents' own level rule. */
enum class LevelMode(val key: String) {
  LV50("50"),
  OPEN("open"),
  TENT("tent");

  companion object {
    fun byKey(key: String?): LevelMode = entries.firstOrNull { it.key == key } ?: LV50
  }
}

/** FRONTIER_MODE_*. */
enum class BattleMode(val key: String) {
  SINGLES("singles"),
  DOUBLES("doubles"),
  MULTIS("multis"),
  LINK_MULTIS("linkmultis");

  companion object {
    fun byKey(key: String?): BattleMode = entries.firstOrNull { it.key == key } ?: SINGLES
  }
}

/** FRONTIER_BRAIN_* (frontier_util.c GetFrontierBrainStatus). */
enum class BrainStatus {
  NOT_READY,
  SILVER,
  GOLD,
  STREAK,
  STREAK_LONG;

  /** Which of the brain's two teams is fought: the silver one for SILVER and STREAK. */
  val goldTeam: Boolean
    get() = this == GOLD || this == STREAK_LONG
}

/**
 * The rules shared by the frontier facilities, transcribed from battle_tower.c and frontier_util.c.
 * Everything takes its randomness from a [Random] so tests can seed it.
 */
object FrontierRules {
  const val FRONTIER_PARTY_SIZE = 3
  const val FRONTIER_DOUBLES_PARTY_SIZE = 4
  const val FRONTIER_MULTI_PARTY_SIZE = 2
  const val FRONTIER_STAGES_PER_CHALLENGE = 7
  const val FRONTIER_MAX_LEVEL_50 = 50
  const val FRONTIER_MIN_LEVEL_OPEN = 60
  const val FRONTIER_MAX_LEVEL_OPEN = 100
  const val TENT_MIN_LEVEL = 30
  const val MAX_BATTLE_FRONTIER_POINTS = 9999
  const val MAX_STREAK = 9999
  /** include/constants/battle_frontier_mons.h: mons above this only appear above level 50. */
  const val FRONTIER_MONS_HIGH_TIER = 849
  const val MAX_TOTAL_EVS = 510
  const val MAX_FRIENDSHIP = 255
  const val MOVE_RETURN = 216
  const val MOVE_FRUSTRATION = 218
  const val NUM_BATTLE_TENT_TRAINERS = 30
  /** A Battle Tent challenge is three battles (battle_tent.c). */
  const val TENT_STAGES = 3

  /** battle_tower.c sFrontierTrainerIdRanges. */
  private val TRAINER_ID_RANGES =
      listOf(
          0 to 99, 80 to 119, 100 to 139, 120 to 159, 140 to 179, 160 to 199, 180 to 219, 200 to 299)

  /** battle_tower.c sFrontierTrainerIdRangesHard: the 7th battle of each challenge. */
  private val TRAINER_ID_RANGES_HARD =
      listOf(
          100 to 119,
          120 to 139,
          140 to 159,
          160 to 179,
          180 to 199,
          200 to 219,
          220 to 239,
          200 to 299)

  /** GetRandomScaledFrontierTrainerId. */
  fun randomScaledTrainerId(challengeNum: Int, battleNum: Int, rng: Random): Int {
    val (lo, hi) =
        if (challengeNum <= 7) {
          if (battleNum == FRONTIER_STAGES_PER_CHALLENGE - 1) TRAINER_ID_RANGES_HARD[challengeNum]
          else TRAINER_ID_RANGES[challengeNum]
        } else TRAINER_ID_RANGES[7]
    return lo + rng.nextInt(hi - lo + 1)
  }

  /** The decomp's re-roll until the trainer has not been fought earlier in this challenge. */
  fun nextTrainerId(challengeNum: Int, battleNum: Int, fought: List<Int>, rng: Random): Int {
    while (true) {
      val id = randomScaledTrainerId(challengeNum, battleNum, rng)
      if (id !in fought) return id
    }
  }

  /** GetFrontierTrainerFixedIvs (and its Dome twin GetDomeTrainerMonIvs). */
  fun fixedIv(trainerId: Int): Int =
      when {
        trainerId <= 99 -> 3
        trainerId <= 119 -> 6
        trainerId <= 139 -> 9
        trainerId <= 159 -> 12
        trainerId <= 179 -> 15
        trainerId <= 199 -> 18
        trainerId <= 219 -> 21
        else -> 31
      }

  /** GetFrontierEnemyMonLevel: 50, or the party's highest level (at least 60) in Open Level. */
  fun enemyLevel(mode: LevelMode, partyMaxLevel: Int): Int =
      when (mode) {
        LevelMode.LV50 -> FRONTIER_MAX_LEVEL_50
        LevelMode.OPEN -> partyMaxLevel.coerceAtLeast(FRONTIER_MIN_LEVEL_OPEN)
        LevelMode.TENT -> partyMaxLevel.coerceAtLeast(TENT_MIN_LEVEL)
      }

  /**
   * FillTrainerParty: [count] random sets from the trainer's [monSet], no duplicate species or
   * held items, and no "high tier" sets (ids above FRONTIER_MONS_HIGH_TIER) at level 50.
   */
  fun fillTrainerParty(
      monSet: List<Int>,
      table: List<FacilityMon>,
      level: Int,
      count: Int,
      rng: Random,
      taken: List<Int> = emptyList(),
  ): List<Int> {
    val chosen = mutableListOf<Int>()
    val all = { taken + chosen }
    var guard = 0
    while (chosen.size < count && guard++ < 10_000) {
      val monId = monSet[rng.nextInt(monSet.size)]
      if (level == FRONTIER_MAX_LEVEL_50 && monId > FRONTIER_MONS_HIGH_TIER) continue
      val mon = table.getOrNull(monId) ?: continue
      if (all().any { table[it].species == mon.species }) continue
      if (mon.item != "NONE" && all().any { table[it].item == mon.item }) continue
      if (monId in chosen) continue
      chosen += monId
    }
    return chosen
  }

  /** sFrontierBrainStreakAppearances: silver streak, gold streak, repeat interval, streak modifier. */
  private val BRAIN_APPEARANCES =
      mapOf(
          Facility.TOWER to intArrayOf(35, 70, 35, 1),
          Facility.DOME to intArrayOf(4, 9, 5, 0),
          Facility.PALACE to intArrayOf(21, 42, 21, 1),
          Facility.ARENA to intArrayOf(28, 56, 28, 1),
          Facility.FACTORY to intArrayOf(21, 42, 21, 1),
          Facility.PIKE to intArrayOf(28, 140, 56, 1),
          Facility.PYRAMID to intArrayOf(21, 70, 35, 0),
      )

  fun brainAppearances(f: Facility): IntArray = BRAIN_APPEARANCES.getValue(f)

  /**
   * GetFrontierBrainStatus: whether the next battle of a [facility] streak (the streak so far,
   * [winStreak]) is against its Frontier Brain. Only singles challenges meet the brains.
   */
  fun brainStatus(facility: Facility, mode: BattleMode, winStreak: Int, symbols: Int): BrainStatus {
    if (mode != BattleMode.SINGLES) return BrainStatus.NOT_READY
    val a = brainAppearances(facility)
    val streak = winStreak + a[3]
    return when (symbols) {
      0,
      1 -> if (streak == a[symbols]) BrainStatus.entries[symbols + 1] else BrainStatus.NOT_READY
      else ->
          when {
            streak == a[0] -> BrainStatus.STREAK
            streak == a[1] -> BrainStatus.STREAK_LONG
            streak > a[1] && (streak - a[1]) % a[2] == 0 -> BrainStatus.STREAK_LONG
            else -> BrainStatus.NOT_READY
          }
    }
  }

  /** frontier_util.c sBattlePointAwards[challenge][facility][battleMode]. */
  private val BP_AWARDS: Array<Array<IntArray>> =
      arrayOf(
          bp(intArrayOf(1, 2, 3, 3), 1, 1, 4, 5, 1, 3, 4, 1, 5),
          bp(intArrayOf(2, 3, 4, 4), 1, 1, 4, 5, 1, 3, 4, 1, 5),
          bp(intArrayOf(3, 4, 5, 5), 2, 2, 5, 6, 1, 4, 5, 2, 6),
          bp(intArrayOf(4, 5, 6, 6), 2, 2, 5, 6, 2, 4, 5, 2, 6),
          bp(intArrayOf(5, 6, 7, 7), 3, 3, 6, 7, 2, 5, 6, 2, 7),
          bp(intArrayOf(6, 7, 8, 8), 3, 3, 6, 7, 2, 5, 6, 4, 7),
          bp(intArrayOf(7, 8, 9, 9), 4, 4, 7, 8, 3, 6, 7, 4, 8),
          bp(intArrayOf(8, 9, 10, 10), 4, 4, 7, 8, 3, 6, 7, 4, 8),
          bp(intArrayOf(9, 10, 11, 11), 5, 5, 8, 9, 4, 7, 8, 8, 9),
          bp(intArrayOf(10, 11, 12, 12), 5, 5, 8, 9, 4, 7, 8, 8, 9),
          bp(intArrayOf(11, 12, 13, 13), 6, 6, 9, 10, 5, 8, 9, 8, 10),
          bp(intArrayOf(12, 13, 14, 14), 6, 6, 9, 10, 6, 8, 9, 8, 10),
          bp(intArrayOf(13, 14, 15, 15), 7, 7, 10, 11, 7, 9, 10, 10, 11),
          bp(intArrayOf(14, 15, 15, 15), 7, 7, 10, 11, 8, 9, 10, 10, 11),
          bp(intArrayOf(15, 15, 15, 15), 8, 8, 11, 12, 9, 10, 11, 10, 12),
          bp(intArrayOf(15, 15, 15, 15), 8, 8, 11, 12, 10, 10, 11, 10, 12),
          bp(intArrayOf(15, 15, 15, 15), 9, 9, 12, 13, 11, 11, 12, 12, 13),
          bp(intArrayOf(15, 15, 15, 15), 9, 9, 12, 13, 12, 11, 12, 12, 13),
          bp(intArrayOf(15, 15, 15, 15), 10, 10, 13, 14, 13, 12, 13, 12, 14),
          bp(intArrayOf(15, 15, 15, 15), 10, 10, 13, 14, 14, 12, 13, 12, 14),
          bp(intArrayOf(15, 15, 15, 15), 11, 11, 14, 15, 15, 13, 14, 12, 15),
          bp(intArrayOf(15, 15, 15, 15), 11, 11, 14, 15, 15, 13, 14, 14, 15),
          bp(intArrayOf(15, 15, 15, 15), 12, 12, 15, 15, 15, 14, 15, 14, 15),
          bp(intArrayOf(15, 15, 15, 15), 12, 12, 15, 15, 15, 14, 15, 14, 15),
          bp(intArrayOf(15, 15, 15, 15), 13, 13, 15, 15, 15, 15, 15, 14, 15),
          bp(intArrayOf(15, 15, 15, 15), 13, 13, 15, 15, 15, 15, 15, 15, 15),
          bp(intArrayOf(15, 15, 15, 15), 14, 14, 15, 15, 15, 15, 15, 15, 15),
          bp(intArrayOf(15, 15, 15, 15), 14, 14, 15, 15, 15, 15, 15, 15, 15),
          bp(intArrayOf(15, 15, 15, 15), 15, 15, 15, 15, 15, 15, 15, 15, 15),
          bp(intArrayOf(15, 15, 15, 15), 15, 15, 15, 15, 15, 15, 15, 15, 15),
      )

  // tower[4], dome[2], palace[2], arena[1], factory[2], pike[1], pyramid[1]
  private fun bp(tower: IntArray, d0: Int, d1: Int, p0: Int, p1: Int, a: Int, f0: Int, f1: Int, pk: Int, py: Int) =
      arrayOf(tower, intArrayOf(d0, d1), intArrayOf(p0, p1), intArrayOf(a), intArrayOf(f0, f1), intArrayOf(pk), intArrayOf(py))

  /**
   * GiveBattlePoints: the BP for a completed challenge. [challengeNum] is the facility's own count
   * (streak / 7, Dome: tournaments won, Pike: streak / 14), counted after the win; a brain adds 10.
   */
  fun battlePoints(facility: Facility, mode: BattleMode, challengeNum: Int, brain: Boolean): Int {
    var c = challengeNum
    if (c != 0) c--
    c = c.coerceAtMost(BP_AWARDS.size - 1)
    val row = BP_AWARDS[c][facility.ordinal]
    val points = row.getOrElse(mode.ordinal) { row[0] }
    return points + if (brain) 10 else 0
  }

  /** gFrontierBannedSpecies is shipped in frontier.json; eggs never enter. */
  data class EntrantCheck(val species: Int, val level: Int, val heldItem: Int, val isEgg: Boolean)

  /**
   * CheckPartyIneligibility / AppendIfValid: the party slots that may enter, in the order the
   * decomp counts them. A slot is valid when it is no egg, no banned species, at most level 50 in
   * Level 50 mode, and repeats neither a species nor a held item of an earlier valid slot. The
   * Pyramid also needs the monster to hold nothing.
   */
  fun eligibleSlots(
      party: List<EntrantCheck>,
      mode: LevelMode,
      banned: Set<Int>,
      pyramid: Boolean = false,
  ): List<Int> {
    val out = mutableListOf<Int>()
    for ((i, m) in party.withIndex()) {
      if (m.isEgg || m.species == 0 || m.species in banned) continue
      if (pyramid && m.heldItem != 0) continue
      if (mode == LevelMode.LV50 && m.level > FRONTIER_MAX_LEVEL_50) continue
      if (out.any { party[it].species == m.species }) continue
      if (m.heldItem != 0 && out.any { party[it].heldItem == m.heldItem }) continue
      out += i
    }
    return out
  }

  /** How many monsters a facility's challenge takes (CheckPartyIneligibility's toChoose). */
  fun partySize(facility: Facility?, mode: BattleMode): Int =
      when (mode) {
        BattleMode.SINGLES -> FRONTIER_PARTY_SIZE
        BattleMode.MULTIS,
        BattleMode.LINK_MULTIS -> FRONTIER_MULTI_PARTY_SIZE
        BattleMode.DOUBLES ->
            if (facility == Facility.TOWER) FRONTIER_DOUBLES_PARTY_SIZE else FRONTIER_PARTY_SIZE
      }

  /**
   * The chosen team as a valid entry: [chosen] are distinct party slots, all eligible, and no two
   * share a species or held item (the selection screen enforces this too).
   */
  fun validEntry(
      party: List<EntrantCheck>,
      chosen: List<Int>,
      mode: LevelMode,
      banned: Set<Int>,
      size: Int,
      pyramid: Boolean = false,
  ): String? {
    if (chosen.size != size) return "Choose $size POKéMON."
    if (chosen.toSet().size != chosen.size) return "Each POKéMON can enter only once."
    for (slot in chosen) {
      val m = party.getOrNull(slot) ?: return "That POKéMON is not in your party."
      if (m.isEgg) return "An EGG can't enter."
      if (m.species in banned) return "That POKéMON is not allowed to enter."
      if (mode == LevelMode.LV50 && m.level > FRONTIER_MAX_LEVEL_50)
          return "Only POKéMON of Lv. 50 or lower may enter."
      if (pyramid && m.heldItem != 0) return "POKéMON entering the PYRAMID can't hold items."
    }
    val picked = chosen.map { party[it] }
    if (picked.map { it.species }.toSet().size != picked.size)
        return "Two POKéMON of the same kind can't enter."
    val items = picked.map { it.heldItem }.filter { it != 0 }
    if (items.toSet().size != items.size) return "Two POKéMON can't hold the same item."
    return null
  }

  /** The EV split of CreateMonWithEVSpreadNatureOTID: 510 / set bits to each set stat. */
  fun evSpread(bits: Int): IntArray {
    val count = (0 until 6).count { bits and (1 shl it) != 0 }.coerceAtLeast(1)
    val each = MAX_TOTAL_EVS / count
    return IntArray(6) { if (bits and (1 shl it) != 0) each else 0 }
  }
}
