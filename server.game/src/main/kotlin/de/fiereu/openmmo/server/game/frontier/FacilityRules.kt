package de.fiereu.openmmo.server.game.frontier

import kotlin.random.Random

/** battle_factory.c: rentals, swaps, opponent teams, fixed IVs. */
object FactoryRules {
  private const val UNOWN = 201
  private const val NUM_FRONTIER_MONS = 882

  /** sFixedIVTable: (normal, last battle of the challenge). */
  private val FIXED_IV = listOf(3 to 6, 6 to 9, 9 to 12, 12 to 15, 15 to 18, 21 to 31, 31 to 31, 31 to 31)

  /** sInitialRentalMonRanges: 8 for Level 50, 8 for Open Level. */
  private val RANGES =
      listOf(
          110 to 199,
          162 to 266,
          267 to 371,
          372 to 467,
          468 to 563,
          564 to 659,
          660 to 755,
          372 to FrontierRules.FRONTIER_MONS_HIGH_TIER,
          372 to 467,
          468 to 563,
          564 to 659,
          660 to 755,
          372 to NUM_FRONTIER_MONS - 1,
          372 to NUM_FRONTIER_MONS - 1,
          372 to NUM_FRONTIER_MONS - 1,
          372 to NUM_FRONTIER_MONS - 1)

  /** GetFactoryMonFixedIV (the shipped bound check: index 8 reads past the table, see note). */
  fun fixedIv(challengeNum: Int, lastBattle: Boolean): Int {
    val set = if (challengeNum >= FIXED_IV.size) FIXED_IV.size - 1 else challengeNum
    return FIXED_IV[set].let { if (lastBattle) it.second else it.first }
  }

  /** GetFactoryMonId. */
  fun monId(mode: LevelMode, challengeNum: Int, better: Boolean, rng: Random): Int {
    val adder = if (mode == LevelMode.LV50) 0 else 8
    val idx =
        if (challengeNum < 7) adder + challengeNum + if (better) 1 else 0 else adder + 7
    val (lo, hi) = RANGES[idx]
    return lo + rng.nextInt(hi - lo + 1)
  }

  /** GetNumPastRentalsRank. */
  fun rentalRank(rents: Int): Int =
      when {
        rents < 15 -> 0
        rents < 22 -> 1
        rents < 29 -> 2
        rents < 36 -> 3
        rents < 43 -> 4
        else -> 5
      }

  /**
   * GenerateInitialRentalMons: six rentals, no Unown, at most one pair of the same species (the
   * decomp's currSpecies quirk), no two with the same held item. The first [rank] come from the
   * better range.
   */
  fun initialRentals(
      table: List<FacilityMon>,
      mode: LevelMode,
      challengeNum: Int,
      rank: Int,
      rng: Random,
  ): List<Int> {
    val ids = mutableListOf<Int>()
    var currSpecies = 0
    var guard = 0
    while (ids.size < 6 && guard++ < 100_000) {
      val monId = monId(mode, challengeNum, ids.size < rank, rng)
      val mon = table[monId]
      if (mon.species == UNOWN) continue
      var bad = false
      for (j in ids) {
        if (j == monId) {
          bad = true
          break
        }
        if (table[j].species == mon.species) {
          if (currSpecies == 0) currSpecies = mon.species
          else {
            bad = true
            break
          }
        }
      }
      if (bad) continue
      if (mon.item != "NONE" && ids.any { table[it].item == mon.item }) {
        if (mon.species == currSpecies) currSpecies = 0
        continue
      }
      ids += monId
    }
    return ids
  }

  /**
   * GenerateOpponentMons: three sets that share no species with the six [rentalPool] sets (the
   * player's and last opponent's), no Unown, no high tier at Level 50, distinct species and items.
   */
  fun opponentMons(
      table: List<FacilityMon>,
      mode: LevelMode,
      challengeNum: Int,
      rentalPool: List<Int>,
      rng: Random,
  ): List<Int> {
    val out = mutableListOf<Int>()
    var guard = 0
    while (out.size < 3 && guard++ < 100_000) {
      val monId = monId(mode, challengeNum, false, rng)
      val mon = table[monId]
      if (mon.species == UNOWN) continue
      if (rentalPool.any { table[it].species == mon.species }) continue
      if (mode == LevelMode.LV50 && monId > FrontierRules.FRONTIER_MONS_HIGH_TIER) continue
      if (out.any { table[it].species == mon.species }) continue
      if (mon.item != "NONE" && out.any { table[it].item == mon.item }) continue
      out += monId
    }
    return out
  }

  /** Rental level: 50, or 100 in Open Level (SetPlayerAndOpponentParties); tents use 30. */
  fun level(mode: LevelMode): Int =
      when (mode) {
        LevelMode.LV50 -> FrontierRules.FRONTIER_MAX_LEVEL_50
        LevelMode.OPEN -> FrontierRules.FRONTIER_MAX_LEVEL_OPEN
        LevelMode.TENT -> FrontierRules.TENT_MIN_LEVEL
      }

  private val STYLE_NEEDED = intArrayOf(3, 3, 3, 2, 2, 2, 2)

  val STYLE_NAMES =
      listOf(
          "free-spirited and unrestrained",
          "total preparation",
          "slow and steady",
          "endurance",
          "high risk, high return",
          "weakening the foe",
          "impossible to predict",
          "depends on the battle's flow",
          "flexibly adapting to the situation")

  /** GetOpponentBattleStyle: 0 none, 1-7 a style, 8 "no singular style" (more than two). */
  fun battleStyle(team: List<FacilityMon>, styleMoves: List<Set<Int>>): Int {
    val points = IntArray(8)
    for (m in team) for (mv in m.moves) {
      val s = styleMoves.indexOfFirst { mv in it }
      points[s + 1]++
    }
    var result = 0
    var count = 0
    for (i in 1 until 8) if (points[i] >= STYLE_NEEDED[i - 1]) {
      result = i
      count++
    }
    return if (count > 2) 8 else result
  }
}

/** PIKE_ROOM_*. */
enum class PikeRoom(val hint: PikeHint) {
  SINGLE_BATTLE(PikeHint.PEOPLE),
  HEAL_FULL(PikeHint.PEOPLE),
  NPC(PikeHint.WHISPERING),
  STATUS(PikeHint.NOSTALGIA),
  HEAL_PART(PikeHint.NOSTALGIA),
  WILD_MONS(PikeHint.POKEMON),
  HARD_BATTLE(PikeHint.POKEMON),
  DOUBLE_BATTLE(PikeHint.WHISPERING),
  BRAIN(PikeHint.BRAIN),
}

/** PIKE_HINT_*, and the hint lady's lines for them (BattleFrontier_BattlePikeThreePathRoom). */
enum class PikeHint(val line: String) {
  WHISPERING("I sense whispering from behind one of these doors."),
  NOSTALGIA("I sense a feeling of nostalgia from behind one of these doors."),
  POKEMON("I sense the presence of POKéMON from behind one of these doors."),
  PEOPLE("I sense the presence of people from behind one of these doors."),
  BRAIN("I sense an overwhelming presence from behind every door..."),
}

/** STATUS1_* inflicted by the Pike's status rooms. */
enum class PikeStatus(val bits: Int, val label: String) {
  TOXIC(0x80, "badly poisoned"),
  FREEZE(0x20, "frozen"),
  PARALYSIS(0x40, "paralyzed"),
  SLEEP(0x03, "asleep"),
  BURN(0x10, "burned"),
}

/** battle_pike.c. */
object PikeRules {
  const val NUM_PIKE_ROOMS = 14

  data class PartyView(val hp: Int, val maxHp: Int, val status: Int, val types: Set<String>, val ability: String, val fullPp: Boolean)

  fun atLeastTwoAlive(party: List<PartyView>): Boolean = party.count { it.hp > 0 } >= 2

  /** AtLeastOneHealthyMon over the first count (1/2/3 by room number) healthy candidates. */
  fun atLeastOneHealthy(party: List<PartyView>): Boolean = party.any { it.status == 0 && it.hp > 0 }

  fun fullHealth(party: List<PartyView>): Boolean =
      party.all { it.hp >= it.maxHp && it.status == 0 && it.fullPp }

  /** The hint for this set of doors (SetHintedRoom). Brain rooms hint at every door. */
  data class Hint(val index: Int, val type: PikeRoom)

  fun hintedRoom(brainNext: Boolean, healingDisabled: Boolean, party: List<PartyView>, rng: Random): Hint {
    if (brainNext) return Hint(rng.nextInt(6), PikeRoom.BRAIN)
    val index = rng.nextInt(3)
    val candidates =
        PikeRoom.entries.filter { it != PikeRoom.BRAIN }.filter {
          !healingDisabled || (it != PikeRoom.HEAL_FULL && it != PikeRoom.HEAL_PART)
        }
    var type = candidates[rng.nextInt(candidates.size)]
    if (type == PikeRoom.STATUS && !atLeastOneHealthy(party)) type = PikeRoom.NPC
    if (type == PikeRoom.DOUBLE_BATTLE && !atLeastTwoAlive(party)) type = PikeRoom.NPC
    return Hint(index, type)
  }

  /** GetNextRoomType: the hinted door is the hinted room; the others never share its hint. */
  fun roomBehind(door: Int, hint: Hint, healingDisabled: Boolean, party: List<PartyView>, rng: Random): PikeRoom {
    if (hint.type == PikeRoom.BRAIN) return PikeRoom.BRAIN
    if (door == hint.index) return hint.type
    val candidates =
        PikeRoom.entries.filter {
          it != PikeRoom.BRAIN &&
              it.hint != hint.type.hint &&
              !(it == PikeRoom.DOUBLE_BATTLE && !atLeastTwoAlive(party)) &&
              !(it == PikeRoom.STATUS && !atLeastOneHealthy(party)) &&
              !(healingDisabled && (it == PikeRoom.HEAL_FULL || it == PikeRoom.HEAL_PART))
        }
    return candidates[rng.nextInt(candidates.size)]
  }

  /** TryInflictRandomStatus: up to 1/2/3 (by room) healthy monsters get one rolled status. */
  fun inflict(party: List<PartyView>, roomNum: Int, rng: Random): Pair<PikeStatus, List<Int>>? {
    val order = (0 until party.size).toMutableList()
    repeat(10) {
      val i = rng.nextInt(party.size)
      val j = rng.nextInt(party.size)
      order[i] = order[j].also { order[j] = order[i] }
    }
    val count = when {
      roomNum <= 4 -> 1
      roomNum <= 9 -> 2
      else -> 3
    }
    val candidates = order.filter { party[it].status == 0 && party[it].hp > 0 }.take(count)
    if (candidates.isEmpty()) return null
    var status: PikeStatus
    var tries = 0
    while (true) {
      val r = rng.nextInt(100)
      status =
          when {
            r < 35 -> PikeStatus.TOXIC
            r < 60 -> PikeStatus.FREEZE
            r < 80 -> PikeStatus.PARALYSIS
            r < 90 -> PikeStatus.SLEEP
            else -> PikeStatus.BURN
          }
      if (candidates.any { !typePrevents(party[it], status) } || ++tries > 50) break
    }
    val hit = candidates.filter { !typePrevents(party[it], status) && !abilityPrevents(party[it], status) }
    return status to hit
  }

  /** Kirlia, except Dusclops for Freeze and half the Burns. */
  fun statusMon(status: PikeStatus, rng: Random): String =
      when (status) {
        PikeStatus.FREEZE -> "DUSCLOPS"
        PikeStatus.BURN -> if (rng.nextInt(2) != 0) "DUSCLOPS" else "KIRLIA"
        else -> "KIRLIA"
      }

  private fun typePrevents(m: PartyView, s: PikeStatus) =
      when (s) {
        PikeStatus.TOXIC -> "STEEL" in m.types || "POISON" in m.types
        PikeStatus.FREEZE -> "ICE" in m.types
        PikeStatus.PARALYSIS -> "GROUND" in m.types || "ELECTRIC" in m.types
        PikeStatus.BURN -> "FIRE" in m.types
        PikeStatus.SLEEP -> false
      }

  private fun abilityPrevents(m: PartyView, s: PikeStatus) =
      when (s) {
        PikeStatus.FREEZE -> m.ability == "MAGMA_ARMOR"
        PikeStatus.BURN -> m.ability == "WATER_VEIL"
        PikeStatus.PARALYSIS -> m.ability == "LIMBER"
        PikeStatus.SLEEP -> m.ability == "INSOMNIA" || m.ability == "VITAL_SPIRIT"
        PikeStatus.TOXIC -> m.ability == "IMMUNITY"
      }

  /** sNumMonsToHealBeforePikeQueen[hintIndex][door]. */
  private val QUEEN_HEALS = listOf(intArrayOf(2, 1, 0), intArrayOf(2, 0, 1), intArrayOf(1, 2, 0), intArrayOf(1, 0, 2), intArrayOf(0, 2, 1), intArrayOf(0, 1, 2))

  fun healsBeforeQueen(hintIndex: Int, door: Int): Int = QUEEN_HEALS[hintIndex][door]

  /** GetBattlePikeWildMonHeaderId. */
  fun wildHeader(streak: Int): Int =
      when {
        streak <= 20 * NUM_PIKE_ROOMS -> 0
        streak <= 40 * NUM_PIKE_ROOMS -> 1
        streak <= 60 * NUM_PIKE_ROOMS -> 2
        else -> 3
      }

  /** TryGenerateBattlePikeWildMon's level. */
  fun wildLevel(mode: LevelMode, partyMax: Int, delta: Int): Int =
      if (mode == LevelMode.LV50) FrontierRules.FRONTIER_MAX_LEVEL_50 - delta
      else if (partyMax < FrontierRules.FRONTIER_MIN_LEVEL_OPEN) FrontierRules.FRONTIER_MIN_LEVEL_OPEN
      else (partyMax - delta).coerceAtLeast(FrontierRules.FRONTIER_MIN_LEVEL_OPEN)

  /** GetPikeQueenFightType(nextRoom): the brain status for the room about to be entered. */
  fun queenStatus(streak: Int, symbols: Int, nextRoom: Int): BrainStatus {
    val a = FrontierRules.brainAppearances(Facility.PIKE)
    val s = streak + nextRoom
    return when (symbols) {
      0,
      1 -> if (s == a[symbols] - a[3]) BrainStatus.entries[symbols + 1] else BrainStatus.NOT_READY
      else ->
          when {
            s == a[0] - a[3] -> BrainStatus.STREAK
            s == a[1] - a[3] || (s > a[1] && (s - a[1] + a[3]) % a[2] == 0) -> BrainStatus.STREAK_LONG
            else -> BrainStatus.NOT_READY
          }
    }
  }
}

/** battle_pyramid.c: floors of a 4x4 grid of squares with trainers, items, an exit and darkness. */
object PyramidRules {
  const val SQUARES = 16
  const val LIGHT_START = 32
  const val LIGHT_STEP = 4
  const val LIGHT_MAX = 120
  const val TOTAL_ROUNDS = 20

  /** The floor template: sPyramidFloorTemplateOptions from sFloorTemplateOffsets[floor]. */
  fun floorTemplate(floor: Int, options: List<Pair<Int, Int>>, rng: Random): Int {
    val offsets = intArrayOf(0, 4, 9, 14, 19, 24, 29)
    val roll = rng.nextInt(100)
    var i = offsets[floor.coerceIn(0, 6)]
    while (i < options.size && roll >= options[i].first) i++
    return options[i.coerceAtMost(options.size - 1)].second
  }

  /** GetPyramidEntranceAndExitSquareIds. */
  fun entranceAndExit(r3: Int, r0: Int): Pair<Int, Int> {
    var e = (r3 and 0xFFFF) % SQUARES
    var x = (r0 and 0xFFFF) % SQUARES
    if (e == x) {
      e = ((r3 and 0xFFFF) + 1) % SQUARES
      x = ((r0 and 0xFFFF) + SQUARES - 1) % SQUARES
    }
    return e to x
  }

  /** SetPickupItem: the item from sPickupItemsLvl*[round] for one pickup roll on [floor]. */
  fun pickup(floor: Int, roll: Int, slots: List<Pair<Int, Int>>, round: List<String>): String {
    val offsets = intArrayOf(0, 9, 18, 27, 36, 45, 54)
    var i = offsets[floor.coerceIn(0, 6)]
    while (i < slots.size - 1 && roll >= slots[i].first) i++
    return round[slots[i].second]
  }

  /** GenerateBattlePyramidWildMon's level: table level +-5, or below the party's in Open Level. */
  fun wildLevel(mode: LevelMode, partyMax: Int, tableLevel: Int, rng: Random): Int {
    val roll = rng.nextInt(11)
    return if (mode == LevelMode.LV50) tableLevel - 5 + roll
    else {
      val lvl = FrontierRules.enemyLevel(LevelMode.OPEN, partyMax)
      (lvl - (FrontierRules.FRONTIER_MAX_LEVEL_50 - tableLevel)).coerceIn(5, 100) - 5 + roll
    }.coerceIn(1, 100)
  }

  /** Squares within the light: the radius in pixels over a square's 64-pixel side. */
  fun visible(at: Int, square: Int, light: Int): Boolean {
    val reach = (light / 32).coerceAtLeast(1)
    val dx = kotlin.math.abs(at % 4 - square % 4)
    val dy = kotlin.math.abs(at / 4 - square / 4)
    return maxOf(dx, dy) <= reach
  }

  fun adjacent(a: Int, b: Int): Boolean {
    val dx = kotlin.math.abs(a % 4 - b % 4)
    val dy = kotlin.math.abs(a / 4 - b / 4)
    return dx + dy == 1
  }
}

/** battle_dome.c: the 16-trainer tournament. */
object DomeRules {
  const val TRAINERS = 16
  const val ROUNDS = 4

  /** sTourneyTreeTrainerIds: the bracket's leaves, top to bottom, as seed (ranking) indices. */
  val BRACKET = intArrayOf(0, 8, 12, 4, 7, 15, 11, 3, 2, 10, 14, 6, 5, 13, 9, 1)

  /** The other half of [leaf]'s subtree in round [round]: the leaves it can meet. */
  fun opponentsLeaves(leaf: Int, round: Int): IntRange {
    val size = 1 shl round
    val base = leaf / (size * 2) * (size * 2)
    val mine = leaf / size % 2
    val start = base + if (mine == 0) size else 0
    return start until start + size
  }

  /** GetTypeEffectivenessPoints with EFFECTIVENESS_MODE_AI_VS_AI / GOOD / BAD. */
  fun effectivenessPoints(multiplier: Int, mode: String): Int {
    // multiplier in 10ths (0, 5 = 1/4, 10 = 1/2, 20 = x1, 40 = x2, 80 = x4)
    return when (mode) {
      "good" ->
          when (multiplier) {
            20 -> 2
            40 -> 4
            80 -> 8
            else -> 0
          }
      "bad" ->
          when (multiplier) {
            0 -> 8
            5 -> 4
            10 -> 2
            40 -> -2
            80 -> -4
            else -> 0
          }
      else ->
          when (multiplier) {
            0 -> -16
            5 -> -8
            20 -> 4
            40 -> 12
            80 -> 20
            else -> 0
          }
    }
  }

  /**
   * SelectOpponentMonsFromParty: the two monsters with the highest points (ties broken by coin),
   * or two random ones when all three are equal.
   */
  fun pickTwo(points: IntArray, rng: Random): List<Int> {
    if (points[0] == points[1] && points[0] == points[2]) {
      val l = mutableListOf(0, 1, 2)
      l.shuffle(rng)
      return l.take(2).sorted()
    }
    val pos = intArrayOf(0, 1, 2)
    val p = points.copyOf()
    for (i in 0 until 2) for (j in i + 1 until 3) {
      if (p[i] < p[j] || (p[i] == p[j] && rng.nextBoolean())) {
        p[i] = p[j].also { p[j] = p[i] }
        pos[i] = pos[j].also { pos[j] = pos[i] }
      }
    }
    return listOf(pos[0], pos[1]).sorted()
  }
}
