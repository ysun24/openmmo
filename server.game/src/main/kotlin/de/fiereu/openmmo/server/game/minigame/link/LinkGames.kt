package de.fiereu.openmmo.server.game.minigame.link

import kotlin.random.Random

/** A player of a link minigame: a human (charId) or an NPC filling an empty slot. */
data class LinkPlayer(val name: String, val charId: Long?, val dex: Int) {
  val npc: Boolean
    get() = charId == null
}

/** The berry index order of gBerryCrush_BerryData (CHERI .. ENIGMA). */
val BERRY_NAMES =
    listOf(
        "CHERI", "CHESTO", "PECHA", "RAWST", "ASPEAR", "LEPPA", "ORAN", "PERSIM", "LUM", "SITRUS",
        "FIGY", "WIKI", "MAGO", "AGUAV", "IAPAPA", "RAZZ", "BLUK", "NANAB", "WEPEAR", "PINAP",
        "POMEG", "KELPSY", "QUALOT", "HONDEW", "GREPA", "TAMATO", "CORNN", "MAGOST", "RABUTA", "NOMEL",
        "SPELON", "PAMTRE", "WATMEL", "DURIN", "BELUE", "LIECHI", "GANLON", "SALAC", "PETAYA", "APICOT",
        "LANSAT", "STARF", "ENIGMA")

/**
 * FireRed's BERRY CRUSH (berry_crush.c). Every player drops a berry: the crusher needs the sum of
 * their gBerryCrush_BerryData difficulties in A presses and yields the sum of their powder. Each
 * input frame, n players pressing together count n + sSyncPressBonus[n - 1]; every 30 frames the
 * presses of that half second decide a big sparkle (sBigSparkleThresholds), and the results'
 * silkiness = bigSparkles * 50 / checks + 50 scales the powder: powder * players * silkiness / 100.
 */
class BerryCrush(val players: List<LinkPlayer>, val berries: List<Int>, private val rnd: Random = Random.Default) {
  val target = berries.sumOf { DIFFICULTY[it.coerceIn(0, DIFFICULTY.size - 1)] }
  private val basePowder = berries.sumOf { POWDER[it.coerceIn(0, POWDER.size - 1)] }
  var frame = 0
    private set

  var total = 0
    private set

  val presses = IntArray(players.size)
  val syncPresses = IntArray(players.size)
  private val pressedThisFrame = BooleanArray(players.size)
  private val lastPress = IntArray(players.size)
  private val prevInterval = IntArray(players.size)
  private val neatStreak = IntArray(players.size)
  val maxNeat = IntArray(players.size)
  private var bigSparkleCounter = 0
  var bigSparkles = 0
    private set

  var bigSparkleChecks = 0
    private set

  val done: Boolean
    get() = total >= target

  /** 0..32, how far the crusher went down (newDepth). */
  val depth: Int
    get() = if (done) 32 else (total * 32 / target.coerceAtLeast(1)).coerceIn(0, 31)

  fun press(i: Int) {
    if (!done && i in players.indices) pressedThisFrame[i] = true
  }

  /** One input frame (BerryCrush_HandlePlayerInput + the 30-frame sparkle check). */
  fun tick() {
    if (done) return
    frame++
    players.forEachIndexed { i, p ->
      if (p.npc && frame - lastPress[i] >= 5 + rnd.nextInt(4)) pressedThisFrame[i] = true
    }
    var n = 0
    for (i in players.indices) {
      if (!pressedThisFrame[i]) continue
      pressedThisFrame[i] = false
      n++
      presses[i]++
      val dt = frame - lastPress[i]
      if (dt in prevInterval[i] - 1..prevInterval[i] + 1) {
        neatStreak[i]++
        if (neatStreak[i] > maxNeat[i]) maxNeat[i] = neatStreak[i]
      } else neatStreak[i] = 0
      prevInterval[i] = dt
      lastPress[i] = frame
    }
    if (n > 1) for (i in players.indices) if (lastPress[i] == frame) syncPresses[i]++
    if (n > 0) {
      bigSparkleCounter += n
      total += n + SYNC_BONUS[n - 1]
    }
    if (frame % 30 == 0) {
      if (bigSparkleCounter > BIG_SPARKLE[(players.size - 2).coerceIn(0, 3)]) bigSparkles++
      bigSparkleCounter = 0
      bigSparkleChecks++
    }
  }

  val silkiness: Int
    get() = ((bigSparkles * 50 / bigSparkleChecks.coerceAtLeast(1)) + 50) and 0x7F

  /** The results' powder, in berry powder units. */
  val powder: Int
    get() = basePowder * players.size * silkiness / 100

  /** Presses per second x 256 (SaveResults' pressingSpeed, Q24.8). */
  val pressingSpeed: Int
    get() = if (frame < 60) 0 else (presses.sum() * 256 * 60 / frame)

  companion object {
    val DIFFICULTY =
        intArrayOf(50, 50, 50, 50, 50, 50, 50, 50, 50, 50, 60, 60, 60, 60, 60, 80, 80, 80, 80, 80,
            100, 100, 100, 100, 100, 130, 130, 130, 130, 130, 160, 160, 160, 160, 160, 180, 180, 180,
            180, 180, 200, 200, 150)
    val POWDER =
        intArrayOf(20, 20, 20, 20, 20, 30, 30, 30, 30, 30, 50, 50, 50, 50, 50, 70, 70, 70, 70, 70,
            100, 100, 100, 100, 100, 150, 150, 150, 150, 150, 250, 250, 250, 250, 250, 500, 500, 500,
            500, 500, 750, 750, 200)
    val SYNC_BONUS = intArrayOf(0, 1, 2, 3, 5)
    val BIG_SPARKLE = intArrayOf(5, 7, 9, 12)
  }
}

/**
 * FireRed's DODRIO BERRY PICKING (dodrio_berry_picking.c). Each player's Dodrio reaches three
 * columns with its heads (left, middle, right; neighbours share the outer ones). Berries fall in
 * MAX_FALL_DIST steps whose delay is sBerryFallDelays (faster as the player's berriesEaten passes
 * sDifficultyThresholds); one can be picked from EAT_FALL_DIST on. Blue / green / gold score
 * 10 / 30 / 50 (sBerryScoreMultipliers), a missed berry costs its column's player 50 and greys a
 * status square; ten grey squares end the game. A best score of PRIZE_SCORE (3000) or more wins a
 * berry from sPrizeBerryIds for the number of players.
 */
class DodrioBerryPicking(val players: List<LinkPlayer>, private val rnd: Random = Random.Default) {
  val columns = players.size * 2 + 1
  /** -1 = no berry, else type 0 blue 1 green 2 gold. */
  val berryType = IntArray(columns) { -1 }
  val fallDist = IntArray(columns)
  private val fallTimer = IntArray(columns)
  private val newBerryTimer = IntArray(columns) { 30 + it * 7 }
  val eaten = IntArray(players.size)
  val picked = Array(players.size) { IntArray(4) } // blue, green, gold, missed
  var graySquares = 0
    private set

  /** Which head each player has out, and until when (frame). */
  val pick = IntArray(players.size) { -1 }
  private val pickUntil = IntArray(players.size)
  private val npcNext = IntArray(players.size) { 20 }
  var frame = 0
    private set

  val over: Boolean
    get() = graySquares >= NUM_STATUS_SQUARES

  /** The columns player [p]'s heads reach: left, middle, right. */
  fun reach(p: Int) = intArrayOf(p * 2, p * 2 + 1, p * 2 + 2)

  private fun difficulty(p: Int): Int = THRESHOLDS.count { eaten[p] >= it }

  private fun owner(col: Int) = (col / 2).coerceAtMost(players.size - 1)

  /** The player pressed left (0), up (1) or right (2). */
  fun pickHead(p: Int, dir: Int) {
    if (over || p !in players.indices || dir !in 0..2) return
    if (frame < pickUntil[p]) return
    pick[p] = dir
    pickUntil[p] = frame + PICK_FRAMES
    val col = reach(p)[dir]
    if (berryType[col] >= 0 && fallDist[col] >= EAT_FALL_DIST) {
      val t = berryType[col]
      picked[p][t]++
      eaten[p]++
      berryType[col] = -1
      newBerryTimer[col] = 20 + rnd.nextInt(40)
    }
  }

  fun tick() {
    if (over) return
    frame++
    for (p in players.indices) if (frame >= pickUntil[p]) pick[p] = -1
    for (c in 0 until columns) {
      if (berryType[c] < 0) {
        if (--newBerryTimer[c] <= 0) spawn(c)
        continue
      }
      val delayRow = (difficulty(owner(c)) / 3).coerceAtMost(FALL_DELAYS.size - 1)
      if (++fallTimer[c] >= FALL_DELAYS[delayRow][berryType[c]]) {
        fallTimer[c] = 0
        if (++fallDist[c] >= MAX_FALL_DIST) {
          picked[owner(c)][BERRY_MISSED]++
          graySquares++
          berryType[c] = -1
          newBerryTimer[c] = 30 + rnd.nextInt(40)
        }
      }
    }
    // NPC Dodrio: pick a ready berry in reach after a short reaction.
    for (p in players.indices) {
      if (!players[p].npc || frame < npcNext[p]) continue
      val r = reach(p)
      val ready = (0..2).filter { berryType[r[it]] >= 0 && fallDist[r[it]] >= EAT_FALL_DIST }
      if (ready.isNotEmpty() && rnd.nextInt(100) < 80) pickHead(p, ready.random(rnd))
      npcNext[p] = frame + 8 + rnd.nextInt(10)
    }
  }

  private fun spawn(c: Int) {
    val d = difficulty(owner(c))
    val roll = rnd.nextInt(100)
    berryType[c] = if (roll < 10 + d * 4) 2 else if (roll < 40 + d * 4) 1 else 0
    fallDist[c] = 0
    fallTimer[c] = 0
  }

  fun score(p: Int): Int {
    val s = picked[p][0] * 10 + picked[p][1] * 30 + picked[p][2] * 50
    val lost = picked[p][BERRY_MISSED] * 50
    return if (s <= lost) 0 else (s - lost).coerceAtMost(MAX_SCORE)
  }

  fun berriesPicked(p: Int) = (picked[p][0] + picked[p][1] + picked[p][2]).coerceAtMost(9999)

  /** SetRandomPrize: one prize for everyone, when the best score reached PRIZE_SCORE. */
  fun prize(): String? {
    if (players.indices.maxOfOrNull { score(it) } ?: 0 < PRIZE_SCORE) return null
    val set = when (players.size) { 4 -> 1; 5 -> 2; else -> 0 }
    return PRIZE_BERRIES[set][rnd.nextInt(PRIZE_BERRIES[set].size)]
  }

  companion object {
    const val MAX_FALL_DIST = 10
    const val EAT_FALL_DIST = 7
    const val NUM_STATUS_SQUARES = 10
    const val BERRY_MISSED = 3
    const val PRIZE_SCORE = 3000
    const val MAX_SCORE = 999990
    const val PICK_FRAMES = 10
    val THRESHOLDS = intArrayOf(5, 10, 20, 30, 50, 70, 100)
    val FALL_DELAYS = arrayOf(intArrayOf(40, 24, 13), intArrayOf(32, 19, 10), intArrayOf(22, 13, 7))
    val PRIZE_BERRIES =
        listOf(
            listOf("RAZZ", "BLUK", "NANAB", "WEPEAR", "PINAP", "PINAP", "WEPEAR", "NANAB", "BLUK", "RAZZ"),
            listOf("POMEG", "KELPSY", "QUALOT", "HONDEW", "GREPA", "TAMATO", "CORNN", "MAGOST", "RABUTA", "NOMEL"),
            listOf("SPELON", "PAMTRE", "WATMEL", "DURIN", "BELUE", "BELUE", "DURIN", "WATMEL", "PAMTRE", "SPELON"))
  }
}

/**
 * FireRed's POKéMON JUMP (pokemon_jump.c). The vine swings round faster and faster
 * (sVineBaseSpeeds + 7 per speed stage); everyone's Pokémon must be in the air when it passes
 * underneath. Each clear scores 10, plus sScoreBonuses[n] when n players jumped together; a
 * Pokémon caught by the vine ends the game. Air time depends on the species' jump type
 * (sPokeJumpMons: slow / normal / fast). The prize is a random sPrizeItems berry, one more for
 * each sPrizeQuantityData score reached.
 */
class PokemonJump(val players: List<LinkPlayer>, val jumpTypes: List<Int>, private val rnd: Random = Random.Default) {
  var frame = 0
    private set

  var score = 0
    private set

  var jumpsInRow = 0
    private set

  var excellentsInRow = 0
    private set

  var bestExcellents = 0
    private set

  var over = false
    private set

  var trippedBy = -1
    private set

  private var speedStage = 0
  private var baseIdx = 0
  /** Frames of one full swing. */
  var period = periodFor(VINE_BASE_SPEEDS[0])
    private set

  /** 0..1 position in the current swing; 0.5 is the bottom. */
  var phase = 0.0
    private set

  private val jumpStart = IntArray(players.size) { -1000 }
  private val npcPlan = IntArray(players.size) { -1 }
  private var swingStartFrame = 0
  private var checked = false

  fun airtime(p: Int) = AIRTIME[jumpTypes.getOrElse(p) { 1 }]

  fun inAir(p: Int) = frame - jumpStart[p] < airtime(p)

  fun jump(p: Int) {
    if (over || p !in players.indices || inAir(p)) return
    jumpStart[p] = frame
  }

  fun tick() {
    if (over) return
    frame++
    val t = frame - swingStartFrame
    phase = t.toDouble() / period
    // NPCs plan one jump a swing, a little before the vine reaches them; as the vine speeds up
    // they sometimes mistime it (2% more per speed stage).
    if (t == 1) {
      for (p in players.indices) {
        if (!players[p].npc) continue
        val ok = rnd.nextInt(100) < 99 - speedStage * 2
        npcPlan[p] = if (ok) period / 2 - airtime(p) / 2 + rnd.nextInt(-5, 6) else period / 2 + 2
      }
    }
    for (p in players.indices) if (players[p].npc && t == npcPlan[p]) jump(p)
    if (!checked && t >= period / 2) {
      checked = true
      val failed = players.indices.firstOrNull { !inAir(it) }
      if (failed != null) {
        over = true
        trippedBy = failed
        return
      }
      jumpsInRow = (jumpsInRow + 1).coerceAtMost(9999)
      score += 10
      // Players whose jumps started within the same few frames count as jumping together.
      val together = players.indices.groupBy { jumpStart[it] / 6 }.values.maxOf { it.size }
      if (together >= 2) {
        score += SCORE_BONUSES[together.coerceAtMost(5)]
        if (together == players.size) {
          excellentsInRow++
          if (excellentsInRow > bestExcellents) bestExcellents = excellentsInRow
        } else excellentsInRow = 0
      } else excellentsInRow = 0
      score = score.coerceAtMost(MAX_JUMP_SCORE)
    }
    if (t >= period) {
      swingStartFrame = frame
      checked = false
      advanceSpeed()
    }
  }

  /** UpdateVineSpeed: through sVineBaseSpeeds, then the next stage adds 7. */
  private fun advanceSpeed() {
    baseIdx++
    if (baseIdx >= VINE_BASE_SPEEDS.size) {
      baseIdx = 0
      speedStage = (speedStage + 1).coerceAtMost(4)
    }
    period = periodFor(VINE_BASE_SPEEDS[baseIdx] + speedStage * 7)
  }

  /** The prize item quantity (sPrizeQuantityData) for [score]. */
  fun prizeQuantity(): Int = PRIZE_QUANTITY.lastOrNull { score >= it.first }?.second ?: 0

  fun prizeItem(): String = PRIZE_ITEMS[rnd.nextInt(PRIZE_ITEMS.size)]

  companion object {
    const val JUMP_SLOW = 0
    const val JUMP_NORMAL = 1
    const val JUMP_FAST = 2
    const val MAX_JUMP_SCORE = 99990
    val VINE_BASE_SPEEDS = intArrayOf(26, 31, 36, 41, 46, 51, 56, 61)
    val SCORE_BONUSES = intArrayOf(0, 0, 50, 100, 200, 500)
    /** Frames in the air per jump type (sJumpOffsets: 48 steps, faster types step quicker). */
    val AIRTIME = intArrayOf(46, 40, 34)
    val PRIZE_ITEMS = listOf("LEPPA", "LUM", "SITRUS", "FIGY", "WIKI", "MAGO", "AGUAV", "IAPAPA")
    val PRIZE_QUANTITY = listOf(5000 to 1, 8000 to 2, 12000 to 3, 16000 to 4, 20000 to 5)

    /** The vine's speed value as frames per swing. */
    fun periodFor(speed: Int) = (5600 / speed).coerceAtLeast(56)

    /** sPokeJumpMons: national dex -> jump type. */
    val SPECIES: Map<Int, Int> =
        mapOf(
            1 to 0, 4 to 2, 7 to 1, 10 to 2, 11 to 2, 13 to 2, 14 to 2, 19 to 2, 20 to 2, 25 to 1,
            27 to 1, 29 to 1, 32 to 1, 35 to 1, 37 to 1, 39 to 0, 43 to 0, 46 to 2, 52 to 1, 54 to 0,
            56 to 2, 58 to 2, 60 to 0, 69 to 0, 90 to 2, 98 to 2, 102 to 0, 104 to 1, 132 to 0,
            133 to 1, 138 to 2, 140 to 2, 152 to 0, 155 to 2, 158 to 1, 167 to 2, 172 to 1, 173 to 1,
            174 to 0, 175 to 0, 179 to 1, 182 to 0, 183 to 0, 191 to 0, 194 to 0, 204 to 0, 209 to 1,
            213 to 0, 216 to 1, 218 to 0, 220 to 1, 228 to 2, 231 to 1, 137 to 1, 236 to 2, 238 to 0,
            239 to 2, 240 to 2, 246 to 2, 252 to 2, 255 to 0, 258 to 1, 259 to 1)
  }
}
