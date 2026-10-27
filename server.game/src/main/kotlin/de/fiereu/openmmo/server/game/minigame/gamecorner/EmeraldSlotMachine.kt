package de.fiereu.openmmo.server.game.minigame.gamecorner

/**
 * Emerald slot machine (pokeemerald src/slot_machine.c): the lucky game, the machine biases drawn
 * per round (DrawMachineBias with sSpecialDrawOdds / sBiasProbabilities_*), the reel stop
 * manipulation (ReelTask_DecideStop: DecideStop_Bias_* / DecideStop_NoBias_*, at most 4 extra
 * turns except when the third reel must lose), CheckMatch / sSlotPayouts, the Pika Power bolts,
 * and REEL TIME (GetReelTimeDraw, ReelTimeSpeed). Pure: [rng] is Random() (a u16).
 *
 * Positions are the decomp's reelPositions: row r (1 top, 2 middle, 3 bottom) shows
 * sReelSymbols[reel][(pos + r) % 21], and each turn of a reel decrements its position.
 */
class EmeraldSlotMachine(
    machineId: Int,
    private val rng: () -> Int,
    lucky: Boolean? = null,
) {
  val machineId = machineId.coerceIn(0, NUM_MACHINES - 1)
  var luckyGame: Boolean = lucky ?: (random() and 1 == 1)
    private set

  var machineBias = 0
    private set

  private var didNotFailBias = false
  var biasSymbol = 0
    private set

  var bet = 0
    private set

  var currentReel = 0
    private set

  var spinning = false
    private set

  var pikaPowerBolts = 0
    private set

  var reelTimeSpinsLeft = 0
    private set

  var reelTimeSpinsUsed = 0
    private set

  /** The last REEL TIME draw (0..5 spins), -1 when the last spin had no REEL TIME. */
  var lastReelTimeDraw = -1
    private set

  /** REEL_NORMAL_SPEED / HALF / QUARTER: pixels per frame of a 24 px symbol. */
  var reelSpeed = REEL_NORMAL_SPEED
    private set

  var netCoinLoss = 0
    private set

  var matches = 0
    private set

  var payout = 0
    private set

  /** Lines that matched: MATCH_* line ids (0 middle, 1 top, 2 bottom, 3 nwse, 4 nesw). */
  val matchLines = mutableListOf<Int>()

  val reelPositions =
      IntArray(NUM_REELS) { INITIAL_POSITIONS[it][if (luckyGame) 1 else 0] % SYMBOLS_PER_REEL }
  private val winnerRows = IntArray(NUM_REELS)
  private val reelExtraTurns = IntArray(NUM_REELS)

  private fun random() = rng() and 0xFFFF

  fun addBet(n: Int): Int {
    if (spinning) return 0
    val take = n.coerceIn(0, MAX_BET - bet)
    bet += take
    return take
  }

  /** SlotTask_StartSpin (+ DrawMachineBias, BeginReelTime when biased toward it). */
  fun spin(): Boolean {
    if (spinning || bet == 0) return false
    // SlotTask_ReadyNewSpin cleared everything but the 7's bias
    machineBias = machineBias and BIAS_7
    drawMachineBias()
    lastReelTimeDraw = -1
    reelSpeed = REEL_NORMAL_SPEED
    if (machineBias and BIAS_REELTIME != 0) {
      reelTime()
      machineBias = machineBias and BIAS_REELTIME.inv()
    } else if (reelTimeSpinsLeft > 0) {
      reelSpeed = reelTimeSpeed()
    }
    // SlotTask_ResetBiasFailure
    didNotFailBias = machineBias != 0
    spinning = true
    currentReel = 0
    payout = 0
    matches = 0
    matchLines.clear()
    return true
  }

  /** The reels moved [frames] frames while spinning. Returns nothing; positions update. */
  fun advanceFrames(frames: Int, subPixels: IntArray) {
    if (!spinning) return
    for (r in currentReel until NUM_REELS) {
      val px = subPixels[r] + frames * reelSpeed
      reelPositions[r] = Math.floorMod(reelPositions[r] - px / REEL_SYMBOL_HEIGHT, SYMBOLS_PER_REEL)
      subPixels[r] = px % REEL_SYMBOL_HEIGHT
    }
  }

  /** ReelTask_DecideStop + MoveToStop for the current reel. Returns the stopped reel. */
  fun stopCurrentReel(): Int {
    check(spinning)
    val reel = currentReel
    winnerRows[reel] = 0
    reelExtraTurns[reel] = 0
    if (reelTimeSpinsLeft == 0) {
      if (machineBias == 0 || !didNotFailBias || !decideStopBias(reel)) {
        didNotFailBias = false
        decideStopNoBias(reel)
      }
    }
    reelPositions[reel] =
        Math.floorMod(reelPositions[reel] - reelExtraTurns[reel], SYMBOLS_PER_REEL)
    reelExtraTurns[reel] = 0
    currentReel++
    if (currentReel >= NUM_REELS) checkMatches()
    return reel
  }

  /** The bolts shown on the Pika Power gauge. */
  private fun checkMatches() {
    spinning = false
    machineBias = machineBias and BIAS_7
    checkMatch()
    if (reelTimeSpinsLeft > 0) {
      reelTimeSpinsLeft--
      reelTimeSpinsUsed++
    }
    if (matches != 0) {
      netCoinLoss = (netCoinLoss - payout).coerceAtLeast(0)
      if (matches and (M_MIXED_7 or M_BLUE_7 or M_RED_7) != 0) {
        machineBias = machineBias and BIAS_7.inv()
        if (matches and (M_BLUE_7 or M_RED_7) != 0) {
          reelTimeSpinsLeft = 0
          reelTimeSpinsUsed = 0
          luckyGame = matches and M_BLUE_7 != 0
        }
      }
      if (matches and M_POWER != 0 && pikaPowerBolts < 16) pikaPowerBolts++
    } else {
      netCoinLoss = (netCoinLoss + bet).coerceAtMost(9999)
    }
    // A REPLAY spins again with the same bet (SlotTask_EndPayout); otherwise a new bet is needed.
    if (matches and M_REPLAY == 0) bet = 0
  }

  /** True when the last round was a REPLAY: the next spin needs no new bet. */
  val replay: Boolean
    get() = !spinning && matches and M_REPLAY != 0 && bet > 0

  // --- biases ---------------------------------------------------------------------------------

  private fun drawMachineBias() {
    if (reelTimeSpinsLeft != 0) return
    if (machineBias and BIAS_7 != 0) return
    if (shouldTrySpecialBias()) {
      val which = trySelectBiasSpecial()
      if (which != BIASES_SPECIAL.size) {
        machineBias = machineBias or BIASES_SPECIAL[which]
        if (which != 1) return
      }
    }
    val which = trySelectBiasRegular()
    if (which != BIASES_REGULAR.size) machineBias = machineBias or BIASES_REGULAR[which]
  }

  private fun shouldTrySpecialBias() = SPECIAL_DRAW_ODDS[machineId][bet - 1] > (random() and 0xFF)

  private fun trySelectBiasSpecial(): Int {
    for (w in BIASES_SPECIAL.indices) {
      if (BIAS_PROB_SPECIAL[w][machineId] > (random() and 0xFF)) return w
    }
    return BIASES_SPECIAL.size
  }

  private fun trySelectBiasRegular(): Int {
    for (w in BIASES_REGULAR.indices) {
      val rval = random() and 0xFF
      var value = BIAS_PROB_REGULAR[w][machineId]
      if (w == 0 && luckyGame) value = (value + 10).coerceAtMost(0x100)
      else if (w == 4 && luckyGame) value = (value - 10).coerceAtLeast(0)
      if (value > rval) return w
    }
    return BIASES_REGULAR.size
  }

  private fun biasSymbolOf(bias: Int): Int {
    var b = bias
    for (i in 0 until 8) {
      if (b and 1 != 0) return BIAS_SYMBOLS[i]
      b = b shr 1
    }
    return 0
  }

  // --- REEL TIME ------------------------------------------------------------------------------

  private fun reelTimeProb(spins: Int) =
      (if (luckyGame) RT_PROB_LUCKY else RT_PROB_NORMAL)[spins][pikaPowerBolts]

  /** ReelTime_Init's GetReelTimeDraw and ReelTime_EndSuccess. */
  private fun reelTime() {
    var draw = 0
    if ((random() and 0xFF) >= reelTimeProb(0)) {
      var spins = 5
      while (spins > 0) {
        if ((random() and 0xFF) < reelTimeProb(spins)) break
        spins--
      }
      draw = spins
    }
    lastReelTimeDraw = draw
    if (draw > 0) pikaPowerBolts = 0 // ReelTime_PikachuReact
    reelTimeSpinsUsed = 0
    reelTimeSpinsLeft = draw
    reelSpeed = if (draw > 0) reelTimeSpeed() else REEL_NORMAL_SPEED
  }

  private fun reelTimeSpeed(): Int {
    val i =
        when {
          netCoinLoss >= 300 -> 4
          netCoinLoss >= 250 -> 3
          netCoinLoss >= 200 -> 2
          netCoinLoss >= 150 -> 1
          else -> 0
        }
    if (random() % 100 < RT_SPEED_PROB[i][0]) return REEL_HALF_SPEED
    val boost = QUARTER_BOOST[reelTimeSpinsUsed.coerceAtMost(QUARTER_BOOST.size - 1)]
    if (random() % 100 < RT_SPEED_PROB[i][1] + boost) return REEL_QUARTER_SPEED
    return REEL_NORMAL_SPEED
  }

  // --- symbols ------------------------------------------------------------------------------

  /** GetSymbol (the reels are aligned when decided). */
  fun symbol(reel: Int, offset: Int): Int =
      REEL_SYMBOLS[reel][Math.floorMod(reelPositions[reel] + offset, SYMBOLS_PER_REEL)]

  // --- DecideStop with bias -----------------------------------------------------------------

  private fun decideStopBias(reel: Int): Boolean =
      when (reel) {
        0 -> {
          var sym2 = biasSymbolOf(machineBias)
          var sym1 = sym2
          if (machineBias and BIAS_7 != 0) {
            sym1 = SYMBOL_7_RED
            sym2 = SYMBOL_7_BLUE
          }
          if (bet == 1) biasReel1Bet1(sym1, sym2) else biasReel1Bet2or3(sym1, sym2)
        }
        1 -> if (bet == 3) biasReel2Bet3() else biasReel2Bet1or2()
        else -> {
          var bs = biasSymbol
          if (machineBias and BIAS_MIXED_7 != 0) {
            bs = if (biasSymbol == SYMBOL_7_RED) SYMBOL_7_BLUE else SYMBOL_7_RED
          }
          if (bet == 3) biasReel3Bet3(bs) else biasReel3Bet1or2(bs)
        }
      }

  private fun eitherAt(pos: Int, s1: Int, s2: Int): Boolean {
    val s = symbol(LEFT, pos)
    if (s == s1 || s == s2) {
      biasSymbol = s
      return true
    }
    return false
  }

  private fun cherriesOnScreen(turns: Int) =
      symbol(LEFT, 1 - turns) == SYMBOL_CHERRY ||
          symbol(LEFT, 2 - turns) == SYMBOL_CHERRY ||
          symbol(LEFT, 3 - turns) == SYMBOL_CHERRY

  private fun biasedTowardCherryOr7s() = machineBias and (BIAS_7 or BIAS_CHERRY) != 0

  private fun biasReel1Bet1(s1: Int, s2: Int): Boolean {
    for (i in 0..MAX_EXTRA_TURNS) {
      if (eitherAt(2 - i, s1, s2)) {
        winnerRows[LEFT] = 2
        reelExtraTurns[LEFT] = i
        return true
      }
    }
    return false
  }

  private fun biasReel1Bet2or3(s1: Int, s2: Int): Boolean {
    val c7 = biasedTowardCherryOr7s()
    if (c7 || !cherriesOnScreen(0)) {
      for (i in 1..3) {
        if (eitherAt(i, s1, s2)) {
          winnerRows[0] = i
          reelExtraTurns[0] = 0
          return true
        }
      }
    }
    for (i in 1..MAX_EXTRA_TURNS) {
      if (c7 || !cherriesOnScreen(i)) {
        if (eitherAt(1 - i, s1, s2)) {
          if (i == 1 && (c7 || !cherriesOnScreen(3))) {
            winnerRows[0] = 3
            reelExtraTurns[0] = 3
            return true
          }
          if (i <= 3 && (c7 || !cherriesOnScreen(i + 1))) {
            winnerRows[0] = 2
            reelExtraTurns[0] = i + 1
            return true
          }
          winnerRows[0] = 1
          reelExtraTurns[0] = i
          return true
        }
      }
    }
    return false
  }

  private fun biasReel2Bet1or2(): Boolean {
    val row = winnerRows[0]
    for (i in 0..MAX_EXTRA_TURNS) {
      if (symbol(MIDDLE, row - i) == biasSymbol) {
        winnerRows[1] = row
        reelExtraTurns[1] = i
        return true
      }
    }
    return false
  }

  private fun biasReel2Bet3(): Boolean {
    if (biasReel2Bet1or2()) {
      if (winnerRows[0] != 2 && reelExtraTurns[1] > 1 && reelExtraTurns[1] != 4) {
        for (i in 0..MAX_EXTRA_TURNS) {
          if (symbol(MIDDLE, 2 - i) == biasSymbol) {
            winnerRows[1] = 2
            reelExtraTurns[1] = i
            break
          }
        }
      }
      return true
    }
    if (winnerRows[0] != 2) {
      for (i in 0..MAX_EXTRA_TURNS) {
        if (symbol(MIDDLE, 2 - i) == biasSymbol) {
          winnerRows[1] = 2
          reelExtraTurns[1] = i
          return true
        }
      }
    }
    return false
  }

  private fun biasReel3Bet1or2(bs: Int): Boolean {
    val row = winnerRows[1]
    for (i in 0..MAX_EXTRA_TURNS) {
      if (symbol(RIGHT, row - i) == bs) {
        winnerRows[2] = row
        reelExtraTurns[2] = i
        return true
      }
    }
    return false
  }

  private fun biasReel3Bet3(bs: Int): Boolean {
    if (winnerRows[0] == winnerRows[1]) return biasReel3Bet1or2(bs)
    val row = if (winnerRows[0] == 1) 3 else 1
    for (i in 0..MAX_EXTRA_TURNS) {
      if (symbol(RIGHT, row - i) == bs) {
        reelExtraTurns[2] = i
        winnerRows[2] = row
        return true
      }
    }
    return false
  }

  // --- DecideStop without bias ----------------------------------------------------------------

  private fun decideStopNoBias(reel: Int) {
    when (reel) {
      0 -> {
        var i = 0
        while (cherriesOnScreen(i)) i++
        reelExtraTurns[0] = i
      }
      1 ->
          when (bet) {
            1 -> noBiasReel2Bet1()
            2 -> noBiasReel2Bet2()
            else -> noBiasReel2Bet3()
          }
      else ->
          when (bet) {
            1 -> noBiasReel3Bet1()
            2 -> noBiasReel3Bet2()
            else -> noBiasReel3Bet3()
          }
    }
  }

  private fun switch7(s: Int): Int? =
      when (s) {
        SYMBOL_7_RED -> SYMBOL_7_BLUE
        SYMBOL_7_BLUE -> SYMBOL_7_RED
        else -> null
      }

  private fun noBiasReel2Bet1() {
    if (winnerRows[0] != 0 && machineBias and BIAS_STRAIGHT_7 != 0) {
      val s = switch7(symbol(LEFT, 2 - reelExtraTurns[0])) ?: return
      for (i in 0..MAX_EXTRA_TURNS) {
        if (s == symbol(MIDDLE, 2 - i)) {
          winnerRows[1] = 2
          reelExtraTurns[1] = i
          break
        }
      }
    }
  }

  private fun noBiasReel2Bet2() {
    if (winnerRows[0] != 0 && machineBias and BIAS_STRAIGHT_7 != 0) {
      val s = switch7(symbol(LEFT, winnerRows[0] - reelExtraTurns[0])) ?: return
      for (i in 0..MAX_EXTRA_TURNS) {
        if (s == symbol(MIDDLE, winnerRows[0] - i)) {
          winnerRows[1] = winnerRows[0]
          reelExtraTurns[1] = i
          break
        }
      }
    }
  }

  private fun noBiasReel2Bet3() {
    if (winnerRows[0] == 0 || machineBias and BIAS_STRAIGHT_7 == 0) return
    if (winnerRows[0] == 2) return noBiasReel2Bet2()
    val s = switch7(symbol(LEFT, winnerRows[0] - reelExtraTurns[0])) ?: return
    var j = if (winnerRows[0] == 3) 3 else 2
    for (i in 0 until 2) {
      if (s == symbol(MIDDLE, j)) {
        winnerRows[1] = j
        reelExtraTurns[1] = 0
        return
      }
      j--
    }
    for (jj in 1..MAX_EXTRA_TURNS) {
      if (s == symbol(MIDDLE, winnerRows[0] - jj)) {
        if (winnerRows[0] == 1) {
          if (jj <= 2) {
            winnerRows[1] = 2
            reelExtraTurns[1] = jj + 1
          } else {
            winnerRows[1] = 1
            reelExtraTurns[1] = jj
          }
        } else {
          if (jj <= 2) {
            winnerRows[1] = 3
            reelExtraTurns[1] = jj
          } else {
            winnerRows[1] = 2
            reelExtraTurns[1] = jj - 1
          }
        }
        return
      }
    }
  }

  private fun mismatched77(a: Int, b: Int) =
      (a == SYMBOL_7_RED && b == SYMBOL_7_BLUE) || (a == SYMBOL_7_BLUE && b == SYMBOL_7_RED)

  private fun mismatched777(a: Int, b: Int, c: Int) =
      (a == SYMBOL_7_RED && b == SYMBOL_7_BLUE && c == SYMBOL_7_RED) ||
          (a == SYMBOL_7_BLUE && b == SYMBOL_7_RED && c == SYMBOL_7_BLUE)

  private fun neitherMatchNor7Mismatch(a: Int, b: Int, c: Int) =
      !(mismatched777(a, b, c) ||
          (a == SYMBOL_7_RED && b == SYMBOL_7_RED && c == SYMBOL_7_BLUE) ||
          (a == SYMBOL_7_BLUE && b == SYMBOL_7_BLUE && c == SYMBOL_7_RED) ||
          (a == b && a == c))

  private fun noBiasReel3Bet1() {
    var i = 0
    val s1 = symbol(LEFT, 2 - reelExtraTurns[0])
    val s2 = symbol(MIDDLE, 2 - reelExtraTurns[1])
    if (s1 == s2) {
      while (true) {
        val s3 = symbol(RIGHT, 2 - i)
        if (!(s1 == s3 || mismatched77(s1, s3) && (s1 == SYMBOL_7_RED || s1 == SYMBOL_7_BLUE)))
            break
        i++
      }
    } else if (mismatched77(s1, s2)) {
      if (machineBias and BIAS_STRAIGHT_7 != 0) {
        for (k in 0..MAX_EXTRA_TURNS) {
          if (s1 == symbol(RIGHT, 2 - k)) {
            reelExtraTurns[2] = k
            return
          }
        }
      }
      i = 0
      while (s1 == symbol(RIGHT, 2 - i)) i++
    }
    reelExtraTurns[2] = i
  }

  private fun noBiasReel3Bet2() {
    var extra = 0
    if (winnerRows[1] != 0 &&
        winnerRows[0] == winnerRows[1] &&
        machineBias and BIAS_STRAIGHT_7 != 0) {
      val s1 = symbol(LEFT, winnerRows[0] - reelExtraTurns[0])
      val s2 = symbol(MIDDLE, winnerRows[1] - reelExtraTurns[1])
      if (mismatched77(s1, s2)) {
        for (i in 0..MAX_EXTRA_TURNS) {
          if (s1 == symbol(RIGHT, winnerRows[1] - i)) {
            extra = i
            break
          }
        }
      }
    }
    while (true) {
      var found = false
      for (i in 1..3) {
        val a = symbol(LEFT, i - reelExtraTurns[0])
        val b = symbol(MIDDLE, i - reelExtraTurns[1])
        val c = symbol(RIGHT, i - extra)
        if (!neitherMatchNor7Mismatch(a, b, c) &&
            !(mismatched777(a, b, c) && machineBias and BIAS_STRAIGHT_7 != 0)) {
          found = true
          break
        }
      }
      if (!found) break
      extra++
    }
    reelExtraTurns[2] = extra
  }

  private fun noBiasReel3Bet3() {
    noBiasReel3Bet2()
    if (winnerRows[1] != 0 &&
        winnerRows[0] != winnerRows[1] &&
        machineBias and BIAS_STRAIGHT_7 != 0) {
      val s1 = symbol(LEFT, winnerRows[0] - reelExtraTurns[0])
      val s2 = symbol(MIDDLE, winnerRows[1] - reelExtraTurns[1])
      if (mismatched77(s1, s2)) {
        val row = if (winnerRows[0] == 1) 3 else 1
        for (i in 0..MAX_EXTRA_TURNS) {
          if (s1 == symbol(RIGHT, row - (reelExtraTurns[2] + i))) {
            reelExtraTurns[2] += i
            break
          }
        }
      }
    }
    fun ok(a: Int, b: Int, c: Int) =
        neitherMatchNor7Mismatch(a, b, c) ||
            (mismatched777(a, b, c) && machineBias and BIAS_STRAIGHT_7 != 0)
    while (!ok(
        symbol(LEFT, 1 - reelExtraTurns[0]),
        symbol(MIDDLE, 2 - reelExtraTurns[1]),
        symbol(RIGHT, 3 - reelExtraTurns[2]))) reelExtraTurns[2]++
    while (!ok(
        symbol(LEFT, 3 - reelExtraTurns[0]),
        symbol(MIDDLE, 2 - reelExtraTurns[1]),
        symbol(RIGHT, 1 - reelExtraTurns[2]))) reelExtraTurns[2]++
  }

  // --- matches --------------------------------------------------------------------------------

  /** CheckMatch: the middle row always, top and bottom from bet 2, the diagonals at bet 3. */
  internal fun checkMatch() {
    matches = 0
    payout = 0
    matchLines.clear()
    fun line(line: Int, r0: Int, r1: Int, r2: Int, topBot: Boolean, diag: Boolean) {
      var m = matchOf(symbol(LEFT, r0), symbol(MIDDLE, r1), symbol(RIGHT, r2))
      if (m == MATCH_NONE) return
      matchLines += line
      if (diag && m == MATCH_CHERRY) return
      if (topBot && m == MATCH_CHERRY) m = MATCH_TOPBOT_CHERRY
      payout += PAYOUTS[m]
      matches = matches or (1 shl m)
    }
    line(LINE_MIDDLE, 2, 2, 2, topBot = false, diag = false)
    if (bet > 1) {
      line(LINE_TOP, 1, 1, 1, topBot = true, diag = false)
      line(LINE_BOTTOM, 3, 3, 3, topBot = true, diag = false)
    }
    if (bet > 2) {
      line(LINE_NWSE, 1, 2, 3, topBot = false, diag = true)
      line(LINE_NESW, 3, 2, 1, topBot = false, diag = true)
    }
  }

  companion object {
    const val NUM_REELS = 3
    const val LEFT = 0
    const val MIDDLE = 1
    const val RIGHT = 2
    const val SYMBOLS_PER_REEL = 21
    const val REEL_SYMBOL_HEIGHT = 24
    const val MAX_BET = 3
    const val MAX_EXTRA_TURNS = 4
    const val NUM_MACHINES = 6

    const val REEL_NORMAL_SPEED = 8
    const val REEL_HALF_SPEED = 4
    const val REEL_QUARTER_SPEED = 2

    const val SYMBOL_7_RED = 0
    const val SYMBOL_7_BLUE = 1
    const val SYMBOL_AZURILL = 2
    const val SYMBOL_LOTAD = 3
    const val SYMBOL_CHERRY = 4
    const val SYMBOL_POWER = 5
    const val SYMBOL_REPLAY = 6

    const val BIAS_REPLAY = 1 shl 0
    const val BIAS_CHERRY = 1 shl 1
    const val BIAS_LOTAD = 1 shl 2
    const val BIAS_AZURILL = 1 shl 3
    const val BIAS_POWER = 1 shl 4
    const val BIAS_REELTIME = 1 shl 5
    const val BIAS_MIXED_7 = 1 shl 6
    const val BIAS_STRAIGHT_7 = 1 shl 7
    const val BIAS_7 = BIAS_STRAIGHT_7 or BIAS_MIXED_7

    const val MATCH_CHERRY = 0
    const val MATCH_TOPBOT_CHERRY = 1
    const val MATCH_REPLAY = 2
    const val MATCH_LOTAD = 3
    const val MATCH_AZURILL = 4
    const val MATCH_POWER = 5
    const val MATCH_MIXED_7 = 6
    const val MATCH_RED_7 = 7
    const val MATCH_BLUE_7 = 8
    const val MATCH_NONE = 9

    const val M_REPLAY = 1 shl MATCH_REPLAY
    const val M_POWER = 1 shl MATCH_POWER
    const val M_MIXED_7 = 1 shl MATCH_MIXED_7
    const val M_RED_7 = 1 shl MATCH_RED_7
    const val M_BLUE_7 = 1 shl MATCH_BLUE_7

    const val LINE_MIDDLE = 0
    const val LINE_TOP = 1
    const val LINE_BOTTOM = 2
    const val LINE_NWSE = 3
    const val LINE_NESW = 4

    /** sSlotPayouts by MATCH_*. */
    val PAYOUTS = intArrayOf(2, 4, 0, 6, 12, 3, 90, 300, 300)

    /** sSymbolToMatch. */
    private val SYMBOL_TO_MATCH =
        intArrayOf(MATCH_RED_7, MATCH_BLUE_7, MATCH_AZURILL, MATCH_LOTAD, MATCH_CHERRY, MATCH_POWER, MATCH_REPLAY)

    /** GetMatchFromSymbols. */
    fun matchOf(a: Int, b: Int, c: Int): Int =
        when {
          a == b && a == c -> SYMBOL_TO_MATCH[a]
          a == SYMBOL_7_RED && b == SYMBOL_7_RED && c == SYMBOL_7_BLUE -> MATCH_MIXED_7
          a == SYMBOL_7_BLUE && b == SYMBOL_7_BLUE && c == SYMBOL_7_RED -> MATCH_MIXED_7
          a == SYMBOL_CHERRY -> MATCH_CHERRY
          else -> MATCH_NONE
        }

    /** sReelSymbols. */
    val REEL_SYMBOLS =
        arrayOf(
            intArrayOf(0, 4, 2, 6, 5, 3, 1, 3, 4, 5, 6, 2, 0, 5, 3, 6, 2, 1, 5, 3, 6),
            intArrayOf(0, 4, 6, 3, 2, 4, 6, 5, 5, 3, 1, 3, 6, 4, 2, 3, 6, 4, 3, 6, 4),
            intArrayOf(0, 5, 1, 6, 3, 2, 6, 3, 5, 2, 6, 3, 2, 5, 6, 3, 2, 5, 6, 3, 4),
        )

    /** sInitialReelPositions [reel][luckyGame]. */
    val INITIAL_POSITIONS = arrayOf(intArrayOf(0, 6), intArrayOf(0, 10), intArrayOf(0, 2))

    val SPECIAL_DRAW_ODDS =
        arrayOf(
            intArrayOf(1, 1, 12), intArrayOf(1, 1, 14), intArrayOf(2, 2, 14),
            intArrayOf(2, 2, 14), intArrayOf(2, 3, 16), intArrayOf(3, 3, 16))

    val BIAS_PROB_SPECIAL =
        arrayOf(
            intArrayOf(25, 25, 30, 40, 40, 50),
            intArrayOf(25, 25, 30, 30, 35, 35),
            intArrayOf(25, 25, 30, 25, 25, 30))

    val BIAS_PROB_REGULAR =
        arrayOf(
            intArrayOf(20, 25, 25, 20, 25, 25),
            intArrayOf(12, 15, 15, 18, 19, 22),
            intArrayOf(25, 25, 25, 30, 30, 40),
            intArrayOf(25, 25, 20, 20, 15, 15),
            intArrayOf(40, 40, 35, 35, 40, 40))

    val BIASES_SPECIAL = intArrayOf(BIAS_STRAIGHT_7, BIAS_REELTIME, BIAS_MIXED_7)
    val BIASES_REGULAR = intArrayOf(BIAS_POWER, BIAS_AZURILL, BIAS_LOTAD, BIAS_CHERRY, BIAS_REPLAY)

    /** sBiasSymbols, by bias bit. */
    val BIAS_SYMBOLS =
        intArrayOf(
            SYMBOL_REPLAY, SYMBOL_CHERRY, SYMBOL_LOTAD, SYMBOL_AZURILL, SYMBOL_POWER,
            SYMBOL_7_RED, SYMBOL_7_RED, SYMBOL_7_RED)

    val RT_PROB_NORMAL =
        arrayOf(
            intArrayOf(243, 243, 243, 80, 80, 80, 80, 40, 40, 40, 40, 40, 40, 5, 5, 5, 5),
            intArrayOf(5, 5, 5, 150, 150, 150, 150, 130, 130, 130, 130, 130, 130, 100, 100, 100, 5),
            intArrayOf(4, 4, 4, 20, 20, 20, 20, 80, 80, 80, 80, 80, 80, 100, 100, 100, 40),
            intArrayOf(2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 45, 45, 45, 100),
            intArrayOf(1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 5, 5, 5, 100),
            intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 6))

    val RT_PROB_LUCKY =
        arrayOf(
            intArrayOf(243, 243, 243, 200, 200, 200, 200, 160, 160, 160, 160, 160, 160, 70, 70, 70, 5),
            intArrayOf(5, 5, 5, 25, 25, 25, 25, 5, 5, 5, 5, 5, 5, 2, 2, 2, 6),
            intArrayOf(4, 4, 4, 25, 25, 25, 25, 30, 30, 30, 30, 30, 30, 40, 40, 40, 35),
            intArrayOf(2, 2, 2, 3, 3, 3, 3, 30, 30, 30, 30, 30, 30, 100, 100, 100, 50),
            intArrayOf(1, 1, 1, 2, 2, 2, 2, 30, 30, 30, 30, 30, 30, 40, 40, 40, 100),
            intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 4, 4, 4, 60))

    val RT_SPEED_PROB =
        arrayOf(intArrayOf(10, 5), intArrayOf(10, 10), intArrayOf(10, 15), intArrayOf(10, 25), intArrayOf(10, 35))

    val QUARTER_BOOST = intArrayOf(0, 5, 10, 15, 20)

    /**
     * GetSlotMachineId (field_specials.c): the machine of Mauville slot [index] (VAR_0x8004).
     * [trendSeed] is dewfordTrends[0].trendiness + .rand; [serviceDay] the POKéNEWS_GAME_CORNER.
     */
    fun machineIdFor(index: Int, trendSeed: Int, serviceDay: Boolean = false): Int {
      val seeds = intArrayOf(12, 2, 4, 5, 1, 8, 7, 11, 3, 10, 9, 6)
      val ids = intArrayOf(0, 1, 1, 2, 2, 2, 3, 3, 3, 4, 4, 5)
      val service = intArrayOf(3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5)
      val rnd = trendSeed + seeds[index.coerceIn(0, 11)]
      return (if (serviceDay) service else ids)[Math.floorMod(rnd, 12)]
    }
  }
}
