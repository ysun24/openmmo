package de.fiereu.openmmo.server.game.contest

import de.fiereu.openmmo.common.enums.PokemonNature

/** A monster's contest stats and ribbons (MON_DATA_COOL..SHEEN, MON_DATA_*_RIBBON). */
data class MonContest(
    val cool: Int = 0,
    val beauty: Int = 0,
    val cute: Int = 0,
    val smart: Int = 0,
    val tough: Int = 0,
    val sheen: Int = 0,
    /** Highest rank ribbon per category, 0 none .. 4 Master (MON_DATA_COOL_RIBBON..). */
    val rankRibbons: List<Int> = listOf(0, 0, 0, 0, 0),
    /** Other ribbons, bits of [Ribbon]. */
    val ribbons: Int = 0,
) {
  fun condition(category: Int): Int =
      when (category) {
        Contest.COOL -> cool
        Contest.BEAUTY -> beauty
        Contest.CUTE -> cute
        Contest.SMART -> smart
        else -> tough
      }

  fun ribbon(category: Int) = rankRibbons.getOrElse(category) { 0 }

  fun withRibbon(category: Int, value: Int) =
      copy(rankRibbons = rankRibbons.toMutableList().also { it[category] = value })

  fun has(r: Int) = ribbons and r != 0

  /** GetRibbonCount. */
  val ribbonCount: Int
    get() = rankRibbons.sum() + Integer.bitCount(ribbons)

  companion object {
    const val MAX_CONDITION = 255
    const val MAX_SHEEN = 255
  }
}

/** Bits of [MonContest.ribbons], in the decomp's order after the rank ribbons. */
object Ribbon {
  const val CHAMPION = 1
  const val WINNING = 2
  const val VICTORY = 4
  const val ARTIST = 8
  const val EFFORT = 16
  val NAMES = listOf("CHAMPION", "WINNING", "VICTORY", "ARTIST", "EFFORT")
}

/** struct Pokeblock. [color] is PBLOCK_CLR_* (1..14). */
data class Pokeblock(
    val color: Int,
    val spicy: Int,
    val dry: Int,
    val sweet: Int,
    val bitter: Int,
    val sour: Int,
    val feel: Int,
) {
  val flavors: List<Int>
    get() = listOf(spicy, dry, sweet, bitter, sour)

  /** GetHighestPokeblocksFlavorLevel: the "LV" the case shows. */
  val level: Int
    get() = flavors.max()

  /** GetPokeblocksFeel. */
  val feelLevel: Int
    get() = feel.coerceAtMost(POKEBLOCK_MAX_FEEL)

  companion object {
    const val POKEBLOCK_MAX_FEEL = 99
    const val CASE_SIZE = 40
    const val BLACK = 12
  }
}

/** src/pokeblock.c + src/use_pokeblock.c: what a Pokéblock does to a monster. */
object PokeblockRules {
  /** PokeblockGetGain: > 0 the nature likes it, < 0 dislikes it. */
  fun gain(nature: PokemonNature, block: Pokeblock, data: ContestData = ContestData.instance): Int {
    val row = data.flavorCompatibility[nature.ordinal]
    var total = 0
    for (f in 0 until 5) {
      val v = block.flavors[f]
      if (v > 0) total += v * row[f]
    }
    return total
  }

  /**
   * GetMonFlavorRelation: +1 for the flavour the nature likes, -1 for the one it dislikes. The
   * conditions map to flavours as sConditionToFlavor: cool spicy, beauty dry, cute sweet, smart
   * bitter, tough sour.
   */
  private fun relation(nature: PokemonNature, flavor: Int, data: ContestData) =
      data.flavorCompatibility[nature.ordinal][flavor]

  /**
   * AddPokeblockToConditions: the new stats after eating [block], or null when the sheen is maxed
   * (the case refuses). CalculatePokeblockEffectiveness boosts the liked/disliked stat by a tenth
   * (rounded) in the direction of the whole block's gain.
   */
  fun feed(
      mon: MonContest,
      nature: PokemonNature,
      block: Pokeblock,
      data: ContestData = ContestData.instance
  ): MonContest? {
    if (mon.sheen >= MonContest.MAX_SHEEN) return null
    // pokeblockStatBoosts in condition order: cool, beauty, cute, smart, tough
    val boosts = intArrayOf(block.spicy, block.dry, block.sweet, block.bitter, block.sour)
    val g = gain(nature, block, data)
    val direction = if (g > 0) 1 else if (g < 0) -1 else 0
    if (direction != 0) {
      for (i in 0 until 5) {
        val amount = boosts[i]
        var boost = amount / 10
        if (amount % 10 >= 5) boost++
        val flavor = relation(nature, i, data)
        if (flavor == direction) boosts[i] += boost * flavor
      }
    }
    fun add(v: Int, d: Int) = (v + d).coerceIn(0, MonContest.MAX_CONDITION)
    return mon.copy(
        cool = add(mon.cool, boosts[0]),
        beauty = add(mon.beauty, boosts[1]),
        cute = add(mon.cute, boosts[2]),
        smart = add(mon.smart, boosts[3]),
        tough = add(mon.tough, boosts[4]),
        sheen = (mon.sheen + block.feel).coerceAtMost(MonContest.MAX_SHEEN),
    )
  }

  /** The "<MON> ate the <BLOCK>" text (pokeblock_feed.c), by gain. */
  fun ateText(nickname: String, block: Pokeblock, nature: PokemonNature): String {
    val name = name(block)
    val g = gain(nature, block)
    return when {
      g == 0 -> "$nickname ate the\n$name."
      g > 0 -> "$nickname happily ate the\n$name."
      else -> "$nickname disdainfully ate the\n$name."
    }
  }

  fun name(block: Pokeblock, data: ContestData = ContestData.instance): String =
      data.pokeblockNames.getOrElse(block.color) { "POKéBLOCK" }

  /** CopyMonFavoritePokeblockName: the color whose single flavour the nature likes, or null. */
  fun favoriteName(nature: PokemonNature, data: ContestData = ContestData.instance): String? {
    for (i in 0 until 5) {
      val row = data.flavorCompatibility[nature.ordinal]
      if (row[i] > 0) return data.pokeblockNames[i + 1]
    }
    return null
  }
}

/**
 * The Berry Blender (src/berry_blender.c) as a frame simulation: the arrow advances by its speed
 * each frame, slows by 1 every 6 frames, each hit changes the speed and the progress bar
 * (UpdateOpponentScores / UpdateSpeedFromHit), NPCs press per Task_HandleOpponent1..3 /
 * Task_HandleBerryMaster, and the game ends when the bar reaches 1000. [CalculatePokeblock] makes
 * the block from every player's berry and the highest RPM.
 */
class BlenderEngine(
    /** Seat count 2..4, players first, then NPCs. */
    val numPlayers: Int,
    /** For each seat: true for an NPC, with the NPC behaviour index (1..3, or 4 for the Master). */
    val npcKind: List<Int>,
    private val rng: ContestRng,
    private val data: ContestData = ContestData.instance,
) {
  var arrowPos = 0
    private set

  var speed = MIN_ARROW_SPEED
    private set

  var progress = 0
    private set

  var maxRPM = 0
    private set

  var frame = 0
    private set

  var perfectOpponents = false
  private var slowdownTimer = 0
  private val npcDidInput = BooleanArray(4)
  private val pendingMiss = IntArray(4) { -1 }
  val scores = Array(numPlayers) { IntArray(3) }
  private val playerIdToArrowId = IntArray(4) { NO_PLAYER }

  /** The last frames' arrow positions, for validating a client's reported press. */
  private val history = ArrayDeque<Int>()

  init {
    require(numPlayers in 2..4)
    val map = PLAYER_ID_MAP[numPlayers - 2]
    for (j in 0 until 4) for (i in 0 until 4) if (map[i] == j) playerIdToArrowId[j] = i
    // sArrowStartPos[sArrowStartPosIds[numPlayers - 2]], after the fall-in spin.
    arrowPos = START_POS[START_POS_IDS[numPlayers - 2]]
  }

  val finished: Boolean
    get() = progress >= MAX_PROGRESS_BAR

  fun arrowIdOf(player: Int) = playerIdToArrowId[player]

  /** GetArrowProximity. */
  fun proximity(pos: Int, player: Int): Int {
    val p = (pos and 0xFFFF) / 256 + 24
    val start = data.arrowHitRangeStart[playerIdToArrowId[player]]
    return if (p >= start && p < start + 48) {
      if (p >= start + 20 && p < start + 28) PROXIMITY_BEST else PROXIMITY_GOOD
    } else PROXIMITY_MISS
  }

  /** A press: the score it earns for the arrow at [pos]. */
  fun scoreFor(pos: Int, player: Int): Int =
      when (proximity(pos, player)) {
        PROXIMITY_BEST -> SCORE_BEST
        PROXIMITY_GOOD -> SCORE_GOOD
        else -> SCORE_MISS
      }

  /** True if [pos] is where the arrow was during the last [HISTORY_FRAMES] frames. */
  fun recentlyAt(pos: Int): Boolean {
    var prev: Int? = null
    for (h in history) {
      if (prev != null) {
        // the arrow swept prev..h this frame (modulo 0x10000)
        val span = (h - prev) and 0xFFFF
        val off = (pos - prev) and 0xFFFF
        if (off <= span) return true
      }
      prev = h
    }
    return history.contains(pos and 0xFFFF)
  }

  /**
   * One frame: [presses] maps a player seat to the arrow position the press was made at. Returns
   * every (seat, score) applied this frame.
   */
  fun step(presses: Map<Int, Int>): List<Pair<Int, Int>> {
    val applied = mutableListOf<Pair<Int, Int>>()
    if (finished) return applied
    frame++
    // UpdateBlenderCenter
    arrowPos = (arrowPos + speed) and 0xFFFF
    history.addLast(arrowPos)
    while (history.size > HISTORY_FRAMES) history.removeFirst()
    val cmds = IntArray(4)
    // HandlePlayerInput (each human seat)
    for ((seat, pos) in presses) if (seat in 0 until numPlayers) cmds[seat] = scoreFor(pos, seat)
    if (++slowdownTimer > 5) {
      if (speed > MIN_ARROW_SPEED) speed--
      slowdownTimer = 0
    }
    // the opponent tasks
    for (seat in 0 until numPlayers) {
      if (pendingMiss[seat] >= 0) {
        if (--pendingMiss[seat] < 0) cmds[seat] = SCORE_MISS
      }
      val kind = npcKind.getOrElse(seat) { 0 }
      if (kind != 0 && cmds[seat] == 0) npcInput(seat, kind)?.let { cmds[seat] = it }
    }
    // UpdateRPM before the hits (CB2_PlayBlender order: input, scores, rpm)
    for (seat in 0 until numPlayers) {
      when (cmds[seat]) {
        SCORE_BEST -> {
          updateSpeedFromHit(SCORE_BEST)
          progress = (progress + speed / 55).coerceAtMost(MAX_PROGRESS_BAR)
          scores[seat][0]++
        }
        SCORE_GOOD -> {
          updateSpeedFromHit(SCORE_GOOD)
          progress += speed / 70
          scores[seat][1]++
        }
        SCORE_MISS -> {
          updateSpeedFromHit(SCORE_MISS)
          if (scores[seat][2] < 999) scores[seat][2]++
        }
        else -> continue
      }
      applied += seat to cmds[seat]
    }
    val rpm = arrowSpeedToRPM(speed)
    if (maxRPM < rpm) maxRPM = rpm
    if (progress >= MAX_PROGRESS_BAR) progress = MAX_PROGRESS_BAR
    return applied
  }

  private fun random() = rng.next() and 0xFFFF

  private fun npcInput(seat: Int, kind: Int): Int? {
    val arrowId = playerIdToArrowId[seat]
    if (kind == 4 || kind == 1) {
      if (proximity(arrowPos, seat) == PROXIMITY_BEST) {
        if (npcDidInput[seat]) return null
        npcDidInput[seat] = true
        if (kind == 4 || perfectOpponents) return SCORE_BEST
        val r = random() / 655
        return when {
          // vanilla bug kept: opponent 1 can't get Best at low speed
          speed < 500 -> SCORE_GOOD
          speed < 1500 ->
              when {
                r > 80 -> SCORE_BEST
                ((r - 21) and 0xFF) < 60 -> SCORE_GOOD
                r < 10 -> {
                  pendingMiss[seat] = 5
                  null
                }
                else -> null
              }
          r <= 90 ->
              when {
                ((r - 71) and 0xFF) < 20 -> SCORE_GOOD
                r < 30 -> {
                  pendingMiss[seat] = 5
                  null
                }
                else -> null
              }
          else -> SCORE_BEST
        }
      }
      npcDidInput[seat] = false
      return null
    }
    // opponents 2 and 3 react a little earlier (the arrow + 0x1800)
    val v = ((arrowPos + 0x1800) and 0xFFFF) shr 8
    val start = data.arrowHitRangeStart[arrowId]
    if (v > start + 20 && v < start + 40) {
      if (npcDidInput[seat]) return null
      npcDidInput[seat] = true
      if (perfectOpponents) return SCORE_BEST
      val r = random() / 655
      var cmd: Int? = null
      if (kind == 2) {
        if (speed < 500) cmd = if (r > 66) SCORE_BEST else SCORE_GOOD
        else {
          if (r > 65) cmd = SCORE_BEST
          if (r in 41..65) cmd = SCORE_GOOD
          if (r < 10) pendingMiss[seat] = 5
        }
      } else {
        if (speed < 500) cmd = if (r > 88) SCORE_BEST else SCORE_GOOD
        else {
          if (r > 60) cmd = SCORE_BEST else if (r in 56..60) cmd = SCORE_GOOD
          if (r < 5) pendingMiss[seat] = 5
        }
      }
      return cmd
    }
    npcDidInput[seat] = false
    return null
  }

  /** UpdateSpeedFromHit. */
  private fun updateSpeedFromHit(cmd: Int) {
    val div = data.speedDivisor[numPlayers]
    when (cmd) {
      SCORE_BEST -> speed += if (speed < 1500) 384 / div else 128 / div
      SCORE_GOOD -> if (speed < 1500) speed += 256 / div
      SCORE_MISS -> {
        speed -= 256 / div
        if (speed < MIN_ARROW_SPEED) speed = MIN_ARROW_SPEED
      }
    }
  }

  companion object {
    const val MAX_PROGRESS_BAR = 1000
    const val MAX_ARROW_POS = 0x10000
    const val MIN_ARROW_SPEED = 0x80
    const val PROXIMITY_GOOD = 0
    const val PROXIMITY_BEST = 1
    const val PROXIMITY_MISS = 2
    const val SCORE_BEST = 0x4523
    const val SCORE_GOOD = 0x5432
    const val SCORE_MISS = 0x2345
    const val NO_PLAYER = 0xFF
    const val HISTORY_FRAMES = 45
    private val PLAYER_ID_MAP =
        listOf(
            intArrayOf(NO_PLAYER, 0, 1, NO_PLAYER),
            intArrayOf(NO_PLAYER, 0, 1, 2),
            intArrayOf(0, 1, 2, 3))
    // sArrowStartPos / sArrowStartPosIds (the arrow points at the first player's mark)
    private val START_POS =
        intArrayOf(0, MAX_ARROW_POS / 4 * 3, MAX_ARROW_POS / 4, MAX_ARROW_POS / 4 * 2)
    private val START_POS_IDS = intArrayOf(1, 1, 0)

    /** ArrowSpeedToRPM (hundredths of an RPM). */
    fun arrowSpeedToRPM(speed: Int): Int = 60 * 60 * 100 * speed / MAX_ARROW_POS

    /**
     * CalculatePokeblock: the block every player receives. [berries] are berry types (1-based), one
     * per seat; [maxRPM] in hundredths.
     */
    fun calculatePokeblock(
        berries: List<Int>,
        maxRPM: Int,
        rng: ContestRng,
        data: ContestData = ContestData.instance
    ): Pokeblock {
      val n = berries.size
      val f = IntArray(6)
      for (b in berries) {
        val d = data.berry(b)
        for (i in 0 until 5) f[i] += d.flavors[i]
        f[5] += d.smoothness
      }
      val first = f[0]
      f[0] -= f[1]
      f[1] -= f[2]
      f[2] -= f[3]
      f[3] -= f[4]
      f[4] -= first
      var negatives = 0
      for (i in 0 until 5) if (f[i] < 0) {
        f[i] = 0
        negatives++
      }
      for (i in 0 until 5) if (f[i] > 0) f[i] = if (f[i] < negatives) 0 else f[i] - negatives
      val mult = maxRPM / 333 + 100
      for (i in 0 until 5) {
        var v = (f[i] * mult) / 10
        val rem = v % 10
        v /= 10
        if (rem > 4) v++
        f[i] = v
      }
      val color = pokeblockColor(berries, f, negatives)
      f[5] = (f[5] / n) - n
      if (f[5] < 0) f[5] = 0
      if (color == Pokeblock.BLACK) {
        val flags = data.blackFlavorFlags[(rng.next() and 0xFFFF) % data.blackFlavorFlags.size]
        for (i in 0 until 5) f[i] = if ((flags shr i) and 1 != 0) 2 else 0
      }
      for (i in 0 until 6) if (f[i] > 255) f[i] = 255
      return Pokeblock(color, f[0], f[1], f[2], f[3], f[4], f[5])
    }

    /** CalculatePokeblockColor (PBLOCK_CLR_*). */
    fun pokeblockColor(berries: List<Int>, flavors: IntArray, negatives: Int): Int {
      val zero = (0 until 5).count { flavors[it] == 0 }
      if (zero == 5 || negatives > 3) return Pokeblock.BLACK
      for (i in berries.indices) for (j in berries.indices) if (i != j && berries[i] == berries[j])
          return Pokeblock.BLACK
      val present = (0 until 5).filter { flavors[it] > 0 }
      if (present.size > 3) return 13 // WHITE
      if (present.size == 3) return 11 // GRAY
      if ((0 until 5).any { flavors[it] > 50 }) return 14 // GOLD
      if (present.size == 1) return present[0] + 1 // RED, BLUE, PINK, GREEN, YELLOW
      if (present.size == 2) {
        val strong = if (flavors[present[0]] >= flavors[present[1]]) present[0] else present[1]
        return strong + 6 // PURPLE, INDIGO, BROWN, LITEBLUE, OLIVE
      }
      return 0
    }

    /**
     * SetOpponentsBerryData: the berry type each NPC seat (1..playersNum-1) puts in, given the
     * player's berry type. [berryMaster] is the Lilycove Berry Master's blender.
     */
    fun opponentBerries(
        playerBerry: Int,
        playersNum: Int,
        berryMaster: Boolean,
        data: ContestData = ContestData.instance
    ): List<Int> {
      val numNpcBerries = 5
      var setId = playerBerry - 1
      if (setId >= numNpcBerries) setId = (setId % numNpcBerries) + numNpcBerries
      val spelon = data.berries.indexOfFirst { it.item == "ITEM_SPELON_BERRY" } + 1
      return (0 until playersNum - 1).map { i ->
        var id = data.blenderOpponentBerrySets[setId][i]
        if (berryMaster) {
          val m = data.berryMasterBerries
          id = m[setId % m.size]
          val diff = (playerBerry - spelon) and 0xFFFF
          if (diff < m.size) id -= m.size
        }
        id + 1
      }
    }
  }
}

/** struct BerryTree (src/berry.c) and its growth rules. */
data class BerryTree(
    val berry: Int = 0,
    val stage: Int = STAGE_NO_BERRY,
    val minutesUntilNextStage: Int = 0,
    val berryYield: Int = 0,
    val regrowthCount: Int = 0,
    val watered: Int = 0,
    val stopGrowth: Boolean = false,
) {
  val wateredCount: Int
    get() = Integer.bitCount(watered and 0xF)

  companion object {
    const val STAGE_NO_BERRY = 0
    const val STAGE_PLANTED = 1
    const val STAGE_SPROUTED = 2
    const val STAGE_TALLER = 3
    const val STAGE_FLOWERING = 4
    const val STAGE_BERRIES = 5
    const val STAGE_SPARKLING = 255
    val BLANK = BerryTree()
  }
}

object BerryTreeRules {
  /** GetStageDurationByBerryType, in minutes. */
  fun stageMinutes(berry: Int, data: ContestData = ContestData.instance) =
      data.berry(berry).stageHours * 60

  /** PlantBerryTree. */
  fun plant(berry: Int, stage: Int, allowGrowth: Boolean, rng: ContestRng): BerryTree {
    var t = BerryTree(berry = berry, stage = stage, minutesUntilNextStage = stageMinutes(berry))
    if (stage == BerryTree.STAGE_BERRIES)
        t =
            t.copy(
                berryYield = yieldOf(t, rng), minutesUntilNextStage = t.minutesUntilNextStage * 4)
    if (!allowGrowth) t = t.copy(stopGrowth = true)
    return t
  }

  /** ObjectEventInteractionWaterBerryTree: the tree after watering, or null if it can't be. */
  fun water(t: BerryTree): BerryTree? =
      when (t.stage) {
        in BerryTree.STAGE_PLANTED..BerryTree.STAGE_FLOWERING ->
            t.copy(watered = t.watered or (1 shl (t.stage - 1)))
        else -> null
      }

  /** CalcBerryYieldInternal. */
  fun yieldOf(t: BerryTree, rng: ContestRng, data: ContestData = ContestData.instance): Int {
    val b = data.berry(t.berry)
    val water = t.wateredCount
    if (water == 0) return b.minYield
    val randMin = (b.maxYield - b.minYield) * (water - 1)
    val randMax = (b.maxYield - b.minYield) * water
    val r = randMin + (rng.next() and 0xFFFF) % (randMax - randMin + 1)
    val extra = if (r % 4 >= 2) r / 4 + 1 else r / 4
    return extra + b.minYield
  }

  /** BerryTreeGrow: false when nothing grew. */
  private fun grow(t: BerryTree, rng: ContestRng): BerryTree? {
    if (t.stopGrowth) return null
    return when (t.stage) {
      BerryTree.STAGE_NO_BERRY -> null
      BerryTree.STAGE_FLOWERING -> t.copy(berryYield = yieldOf(t, rng), stage = t.stage + 1)
      BerryTree.STAGE_PLANTED,
      BerryTree.STAGE_SPROUTED,
      BerryTree.STAGE_TALLER -> t.copy(stage = t.stage + 1)
      BerryTree.STAGE_BERRIES -> {
        val n = t.regrowthCount + 1
        if (n == 10) BerryTree.BLANK
        else
            t.copy(watered = 0, berryYield = 0, stage = BerryTree.STAGE_SPROUTED, regrowthCount = n)
      }
      else -> null
    }
  }

  /** BerryTreeTimeUpdate for one tree. */
  fun advance(tree: BerryTree, minutes: Int, rng: ContestRng): BerryTree {
    var t = tree
    if (t.berry == 0 || t.stage == 0 || t.stopGrowth || minutes <= 0) return t
    if (minutes >= stageMinutes(t.berry) * 71) return BerryTree.BLANK
    var time = minutes
    while (time != 0) {
      if (t.minutesUntilNextStage > time) {
        t = t.copy(minutesUntilNextStage = t.minutesUntilNextStage - time)
        break
      }
      time -= t.minutesUntilNextStage
      t = t.copy(minutesUntilNextStage = stageMinutes(t.berry))
      t = grow(t, rng) ?: break
      if (t.berry == 0) break
      if (t.stage == BerryTree.STAGE_BERRIES)
          t = t.copy(minutesUntilNextStage = t.minutesUntilNextStage * 4)
    }
    return t
  }
}
