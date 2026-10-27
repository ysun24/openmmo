package de.fiereu.openmmo.server.game.minigame.gamecorner

/**
 * FireRed / LeafGreen slot machine (pokefirered src/slot_machine.c): three reels of 21 icons, the
 * machine bias drawn per spin from sReelBiasChances[machineIdx] (CalcSlotBias), the reel stop
 * manipulation of StopReel1/2/3 (up to 4 icons of slide), and CalcPayout over the five lines the
 * bet lights. Pure: [rng] is the decomp's Random() (a u16).
 *
 * Reel positions follow the decomp: a reel spins by decrementing its position, one icon per three
 * frames (Task_SpinReels), and the visible column of reel r at position p is icons
 * p+1, p+2, p+3 (top, middle, bottom).
 */
class FrlgSlotMachine(machineIdx: Int, private val rng: () -> Int) {
  val machineIdx: Int = if (machineIdx in BIAS_CHANCES.indices) machineIdx else 0
  var machineBias = PAYOUT_NONE
    private set

  var biasCooldown = 0
    private set

  var bet = 0
    private set

  val reelPositions = IntArray(NUM_REELS)
  private val reelStopOrder = IntArray(NUM_REELS)
  var currentReel = 0
    private set

  var spinning = false
    private set

  var payout = 0
    private set

  var rewardClass = PAYOUT_NONE
    private set

  val winFlags = BooleanArray(NUM_MATCH_LINES)

  /** Adds [n] to the bet (DPAD_DOWN adds 1, R adds up to 3); returns how much was taken. */
  fun addBet(n: Int): Int {
    if (spinning) return 0
    val take = n.coerceIn(0, MAX_BET - bet)
    bet += take
    return take
  }

  /** Starts a spin (state 2 of MainTask_SlotsGameLoop). Needs a bet. */
  fun spin(): Boolean {
    if (spinning || bet == 0) return false
    calcSlotBias()
    spinning = true
    currentReel = 0
    payout = 0
    rewardClass = PAYOUT_NONE
    winFlags.fill(false)
    return true
  }

  /** The reels moved [icons] icons while spinning (one per three frames). */
  fun advance(icons: Int) {
    if (!spinning) return
    for (r in currentReel until NUM_REELS) {
      reelPositions[r] = Math.floorMod(reelPositions[r] - icons, REEL_LENGTH)
    }
  }

  /**
   * The player pressed A on the current reel: decide where it stops (StopReel1/2/3) and stop it
   * there. After the third reel, the payout is computed. Returns the stopped reel.
   */
  fun stopCurrentReel(): Int {
    check(spinning)
    val reel = currentReel
    when (reel) {
      0 -> stopReel1(reel)
      1 -> stopReel2(reel)
      else -> stopReel3(reel)
    }
    currentReel++
    if (currentReel >= NUM_REELS) {
      rewardClass = calcPayout()
      bet = 0
      spinning = false
      if (rewardClass != PAYOUT_NONE) machineBias = PAYOUT_NONE // ResetMachineBias
    }
    return reel
  }

  private fun random() = rng() and 0xFFFF

  /** CalcSlotBias. */
  internal fun calcSlotBias() {
    val rval = random() / 4
    val chances = BIAS_CHANCES[machineIdx]
    var i = 0
    while (i < NUM_PAYOUT_TYPES - 1) {
      if (rval < chances[i]) break
      i++
    }
    if (machineBias < PAYOUT_ROCKET) {
      if (biasCooldown == 0) {
        if ((random() and 0x3FFF) < chances[PAYOUT_7]) biasCooldown = if (random() and 1 != 0) 5 else 60
      }
      if (biasCooldown != 0) {
        if (i == 0 && (random() and 0x3FFF) < (0.7 * 0x3FFF).toInt())
            biasCooldown = if (random() and 1 != 0) 5 else 60
        biasCooldown--
      }
      machineBias = i
    }
  }

  // GetNextReelPosition: the reels are aligned to an icon when stopped here (no subpixel).
  private fun nextPos(reel: Int) = reelPositions[reel]

  private fun icon(reel: Int, pos: Int) = REEL_ICONS[reel][Math.floorMod(pos, REEL_LENGTH)]

  private fun stopReel1(reel: Int) {
    val next = nextPos(reel)
    val sample = ArrayList<Int>(5)
    if (machineBias == 0 && reel == 0) {
      for (i in 0 until 5) {
        var j = 0
        var dest = next - i + 1
        while (j < 3) {
          if (dest >= REEL_LENGTH) dest = 0
          if (testAttr(1, icon(reel, dest))) break
          j++
          dest++
        }
        if (j == 3) sample += i
      }
    } else if (machineBias != 1 || reel == 0) {
      var dest = next + 1
      for (i in 0 until 3) {
        if (dest >= REEL_LENGTH) dest = 0
        if (testAttr(machineBias, icon(reel, dest))) {
          sample.clear()
          sample += 0
          break
        }
        dest++
      }
      dest = next
      for (i in 0 until 4) {
        if (dest < 0) dest = REEL_LENGTH - 1
        if (testAttr(machineBias, icon(reel, dest))) sample += i + 1
        dest--
      }
    }
    val slide = if (sample.isEmpty()) random() % 5 else sample[random() % sample.size]
    reelStopOrder[0] = reel
    reelPositions[reel] = Math.floorMod(next - slide, REEL_LENGTH)
  }

  private fun stopReel2(reel: Int) {
    val firstId = reelStopOrder[0]
    var firstPos = reelPositions[firstId] + 1
    if (firstPos >= REEL_LENGTH) firstPos = 0
    val next = nextPos(reel)
    var pos = next + 1
    if (pos >= REEL_LENGTH) pos = 0
    val possible = ArrayList<Int>(5)
    for (i in 0 until 5) {
      if (twoReelBiasCheck(firstId, firstPos, reel, pos, machineBias)) possible += i
      pos--
      if (pos < 0) pos = REEL_LENGTH - 1
    }
    val slide =
        if (possible.isEmpty()) {
          if (machineBias == PAYOUT_ROCKET || machineBias == PAYOUT_7) 4 else 0
        } else possible[0]
    reelStopOrder[1] = reel
    reelPositions[reel] = Math.floorMod(next - slide, REEL_LENGTH)
  }

  private fun stopReel3(reel: Int) {
    val next = nextPos(reel)
    var test = next
    val possible = ArrayList<Int>(5)
    for (i in 0 until 5) {
      if (oneReelBiasCheck(reel, test, machineBias)) possible += i
      test--
      if (test < 0) test = 20
    }
    val slide =
        if (possible.isEmpty()) {
          if (machineBias == PAYOUT_ROCKET || machineBias == PAYOUT_7) 4 else 0
        } else possible[0]
    reelPositions[reel] = Math.floorMod(next - slide, REEL_LENGTH)
  }

  private fun twoReelBiasCheck(r0: Int, p0In: Int, r1: Int, p1In: Int, bias: Int): Boolean {
    val icons = IntArray(9) { 7 }
    var p0 = p0In
    var p1 = p1In
    for (i in 0 until 3) {
      icons[3 * r0 + i] = icon(r0, p0)
      icons[3 * r1 + i] = icon(r1, p1)
      if (++p0 >= REEL_LENGTH) p0 = 0
      if (++p1 >= REEL_LENGTH) p1 = 0
    }
    when (bias) {
      0 -> {
        for (i in 0 until 3) if (testAttr(1, icons[i])) return false
        for (pair in SECOND_REEL_CHECK) if (icons[pair[0]] == icons[pair[1]]) return true
        return false
      }
      1 -> {
        if (r0 == 0 || r1 == 0) {
          if (r0 == 1 || r1 == 1) {
            var i = 0
            while (i < 15) {
              if (icons[SECOND_REEL_CHECK[i][0]] == icons[SECOND_REEL_CHECK[i][1]]) return false
              i += 3
            }
          }
          for (i in 0 until 3) if (testAttr(bias, icons[i])) return true
          return false
        }
        return true
      }
      2 -> {
        if (r0 == 2 || r1 == 2) {
          for (i in 0 until 9) if (testAttr(bias, icons[i])) return true
          return false
        }
      }
    }
    for (pair in SECOND_REEL_CHECK) {
      if (icons[pair[0]] == icons[pair[1]] && testAttr(bias, icons[pair[0]])) return true
    }
    return false
  }

  private fun oneReelBiasCheck(reelId: Int, reelPosIn: Int, bias: Int): Boolean {
    val icons = IntArray(9)
    var first = reelPositions[reelStopOrder[0]] + 1
    var second = reelPositions[reelStopOrder[1]] + 1
    var reelPos = reelPosIn + 1
    if (first >= REEL_LENGTH) first = 0
    if (second >= REEL_LENGTH) second = 0
    if (reelPos >= REEL_LENGTH) reelPos = 0
    for (i in 0 until 3) {
      icons[reelStopOrder[0] * 3 + i] = icon(reelStopOrder[0], first)
      icons[reelStopOrder[1] * 3 + i] = icon(reelStopOrder[1], second)
      icons[reelId * 3 + i] = icon(reelId, reelPos)
      if (++first >= REEL_LENGTH) first = 0
      if (++second >= REEL_LENGTH) second = 0
      if (++reelPos >= REEL_LENGTH) reelPos = 0
    }
    fun line3(l: IntArray) = icons[l[0]] == icons[l[1]] && icons[l[0]] == icons[l[2]]
    when (bias) {
      PAYOUT_NONE -> {
        for (i in 0 until 3) if (testAttr(1, icons[i])) return false
        for (l in THIRD_REEL_CHECK) if (line3(l)) return false
        return true
      }
      PAYOUT_CHERRIES2 -> {
        for (l in THIRD_REEL_CHECK) {
          if (icons[l[0]] == icons[l[1]] && testAttr(bias, icons[l[0]])) return false
        }
        for (i in 0 until 3) if (testAttr(bias, icons[i])) return true
        return false
      }
      PAYOUT_CHERRIES3 -> {
        for (l in THIRD_REEL_CHECK) {
          if (icons[l[0]] == icons[l[1]] && testAttr(bias, icons[l[0]])) return true
        }
        return false
      }
    }
    for (l in THIRD_REEL_CHECK) if (line3(l) && testAttr(bias, icons[l[0]])) return true
    return false
  }

  /** The 3x3 visible icons, [reel * 3 + row] (row 0 top). */
  fun visibleIcons(): IntArray =
      IntArray(9) { k -> icon(k / 3, reelPositions[k / 3] + 1 + k % 3) }

  /** CalcPayout: sets [payout] and [winFlags], returns the best match class. */
  internal fun calcPayout(): Int {
    winFlags.fill(false)
    val vis = visibleIcons()
    payout = 0
    var best = 0
    for (i in 0 until NUM_MATCH_LINES) {
      val row = ROW_ATTRS[i]
      if (bet >= row[3]) {
        val a = vis[row[0]]
        val cur =
            when {
              testAttr(1, a) -> if (testAttr(2, vis[row[1]])) 2 else 1
              a == vis[row[1]] && a == vis[row[2]] -> payoutRank(a)
              else -> 0
            }
        if (cur != 0) {
          winFlags[i] = true
          payout += PAYOUT_TABLE[cur]
        }
        if (cur > best) best = cur
      }
    }
    return best
  }

  companion object {
    const val NUM_REELS = 3
    const val REEL_LENGTH = 21
    const val NUM_MATCH_LINES = 5
    const val MAX_BET = 3

    /** Frames per icon while spinning (Task_SpinReels: subpixel 0..2). */
    const val FRAMES_PER_ICON = 3

    const val ICON_7 = 0
    const val ICON_ROCKET = 1
    const val ICON_PIKACHU = 2
    const val ICON_PSYDUCK = 3
    const val ICON_CHERRIES = 4
    const val ICON_MAGNEMITE = 5
    const val ICON_SHELLDER = 6

    const val PAYOUT_NONE = 0
    const val PAYOUT_CHERRIES2 = 1
    const val PAYOUT_CHERRIES3 = 2
    const val PAYOUT_MAGSHELL = 3
    const val PAYOUT_PIKAPSY = 4
    const val PAYOUT_ROCKET = 5
    const val PAYOUT_7 = 6
    const val NUM_PAYOUT_TYPES = 7

    val PAYOUT_TABLE = intArrayOf(0, 2, 6, 8, 15, 100, 300)

    /** sReelBiasChances, one row per machine (6 machines). */
    val BIAS_CHANCES =
        arrayOf(
            intArrayOf(0x1fa1, 0x2eab, 0x3630, 0x39f3, 0x3bd4, 0x3bfc, 0x0049),
            intArrayOf(0x1f97, 0x2ea2, 0x3627, 0x39e9, 0x3bca, 0x3bf8, 0x0049),
            intArrayOf(0x1f91, 0x2e9b, 0x3620, 0x39e3, 0x3bc4, 0x3bf4, 0x0049),
            intArrayOf(0x1f87, 0x2e92, 0x3617, 0x39d9, 0x3bba, 0x3bef, 0x0050),
            intArrayOf(0x1f7f, 0x2e89, 0x360e, 0x39d1, 0x3bb2, 0x3bea, 0x0050),
            intArrayOf(0x1fc9, 0x2efc, 0x3696, 0x3a63, 0x3c49, 0x3c8b, 0x0073),
        )

    /** sReelIconAnimByReelAndPos. */
    val REEL_ICONS =
        arrayOf(
            intArrayOf(0, 3, 4, 1, 2, 6, 2, 5, 0, 6, 3, 1, 4, 2, 6, 0, 5, 2, 1, 6, 2),
            intArrayOf(0, 5, 4, 3, 1, 5, 4, 3, 2, 5, 4, 3, 0, 5, 4, 1, 3, 6, 5, 3, 4),
            intArrayOf(0, 3, 6, 5, 2, 3, 6, 5, 2, 3, 5, 6, 2, 3, 5, 6, 2, 3, 5, 6, 1),
        )

    /** sRowAttributes: col1, col2, col3 index into the 3x3 grid, min bet. */
    val ROW_ATTRS =
        arrayOf(
            intArrayOf(0, 4, 8, 3), // tl-br
            intArrayOf(0, 3, 6, 2), // top row
            intArrayOf(1, 4, 7, 1), // middle row
            intArrayOf(2, 5, 8, 2), // bottom row
            intArrayOf(2, 4, 6, 3), // bl-tr
        )

    val SECOND_REEL_CHECK =
        arrayOf(
            intArrayOf(0, 3), intArrayOf(0, 6), intArrayOf(3, 6),
            intArrayOf(1, 4), intArrayOf(1, 7), intArrayOf(4, 7),
            intArrayOf(2, 5), intArrayOf(2, 8), intArrayOf(5, 8),
            intArrayOf(0, 4), intArrayOf(0, 8), intArrayOf(4, 8),
            intArrayOf(2, 4), intArrayOf(2, 6), intArrayOf(4, 6),
        )

    val THIRD_REEL_CHECK =
        arrayOf(
            intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
            intArrayOf(0, 4, 8), intArrayOf(2, 4, 6),
        )

    /** sSlotMachineIndices (field_specials.c GetRandomSlotMachineId). */
    val MACHINE_INDICES = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 2, 2, 2, 3, 3, 3, 4, 4, 5)

    /** TestReelIconAttribute. */
    fun testAttr(attr: Int, icon: Int): Boolean =
        when (attr) {
          PAYOUT_NONE -> icon != ICON_CHERRIES
          PAYOUT_CHERRIES2, PAYOUT_CHERRIES3 -> icon == ICON_CHERRIES
          PAYOUT_MAGSHELL -> icon == ICON_MAGNEMITE || icon == ICON_SHELLDER
          PAYOUT_PIKAPSY -> icon == ICON_PIKACHU || icon == ICON_PSYDUCK
          PAYOUT_ROCKET -> icon == ICON_ROCKET
          PAYOUT_7 -> icon == ICON_7
          else -> false
        }

    /** ReelIconToPayoutRank. */
    fun payoutRank(icon: Int): Int =
        when (icon) {
          ICON_MAGNEMITE, ICON_SHELLDER -> PAYOUT_MAGSHELL
          ICON_PIKACHU, ICON_PSYDUCK -> PAYOUT_PIKAPSY
          ICON_ROCKET -> PAYOUT_ROCKET
          ICON_7 -> PAYOUT_7
          else -> PAYOUT_CHERRIES2
        }
  }
}
