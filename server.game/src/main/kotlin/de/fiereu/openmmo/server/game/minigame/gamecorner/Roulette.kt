package de.fiereu.openmmo.server.game.minigame.gamecorner

/**
 * Emerald's ROULETTE (pokeemerald src/roulette.c). A round is six balls (BALLS_PER_ROUND). Before
 * each ball the player picks one grid selection: a single square (x12), a Pokémon column or a color
 * row, whose multiplier (GetMultiplier) falls as squares of it are hit. Each ball costs the table's
 * minimum bet (sTableMinBets: left table 1, right 3; the special-rate tables 1 and 6) and a hit
 * pays minBet x multiplier (Task_PrintPayout). A square is taken once a ball lands in it, for the
 * rest of the round (RecordHit).
 *
 * The decomp simulates the ball's flight around a rotating wheel; here the wheel angle comes from
 * the time the player pressed A and the travel distance from sRouletteTables' randDistanceLow ..
 * randDistanceHigh (GetRandomForBallTravelDistance), and a ball reaching a taken square rolls on to
 * the next free one, which is what the Shroomish / Taillow bounce does on the GBA.
 */
class Roulette(val table: Int, val specialRate: Boolean, private val rng: () -> Int) {
  val minBet: Int = TABLE_MIN_BETS[(table and 1) + if (specialRate) 2 else 0]

  /** Balls thrown this round (0..6). */
  var ballNum = 0
    private set

  var hitFlags = 0
    private set

  val hitSquares = IntArray(BALLS_PER_ROUND)
  val pokeHits = IntArray(NUM_BOARD_POKES)
  val colorHits = IntArray(NUM_BOARD_COLORS)

  /** The last ball's slot (0..11) and grid square, -1 before the first. */
  var lastSlot = -1
    private set

  var lastSquare = -1
    private set

  val roundOver: Boolean
    get() = ballNum >= BALLS_PER_ROUND

  fun newRound() {
    ballNum = 0
    hitFlags = 0
    hitSquares.fill(0)
    pokeHits.fill(0)
    colorHits.fill(0)
    lastSlot = -1
    lastSquare = -1
  }

  /** GetMultiplier. */
  fun multiplier(selection: Int): Int {
    if (selection !in 1..NUM_GRID_SELECTIONS) return 0
    val col = selection % (NUM_BOARD_POKES + 1)
    val row = selection / (NUM_BOARD_POKES + 1)
    return when {
      row == 0 -> { // a column (COL_*)
        val i = col - 1
        if (pokeHits[i] >= NUM_BOARD_COLORS) 0 else MULTIPLIERS[pokeHits[i] + 2]
      }
      col == 0 -> { // a row (ROW_*)
        val i = row - 1
        if (colorHits[i] >= NUM_BOARD_POKES) 0 else MULTIPLIERS[colorHits[i] + 1]
      }
      else -> if (hitFlags and (1 shl selection) != 0) 0 else MAX_MULTIPLIER
    }
  }

  data class Throw(
      val slot: Int,
      val square: Int,
      val won: Boolean,
      val multiplier: Int,
      val payout: Int
  )

  /**
   * Throws the next ball for bet [selection] with the wheel at [wheelAngle] degrees. Returns the
   * outcome; the caller has already taken [minBet] coins.
   */
  fun throwBall(selection: Int, wheelAngle: Int): Throw {
    require(!roundOver) { "round over" }
    require(selection in 1..NUM_GRID_SELECTIONS) { "no bet" }
    val mult = multiplier(selection)
    val t = ROULETTE_TABLES[table and 1]
    val travel = t.first + (rng() and 0xFFFF) % (t.second - t.first + 1)
    var slot = Math.floorMod(wheelAngle + travel, 360) / DEGREES_PER_SLOT
    var tries = 0
    while (hitFlags and SLOT_FLAGS[slot] != 0 && tries++ < NUM_ROULETTE_SLOTS) {
      slot = (slot + 1) % NUM_ROULETTE_SLOTS
    }
    val square = recordHit(slot)
    val won = isHit(square, selection)
    lastSlot = slot
    lastSquare = square
    return Throw(slot, square, won, mult, if (won) minBet * mult else 0)
  }

  /** RecordHit. */
  private fun recordHit(slot: Int): Int {
    val square = SLOT_SQUARES[slot]
    hitSquares[ballNum++] = square
    hitFlags = hitFlags or (1 shl square)
    val col = square % (NUM_BOARD_POKES + 1) - 1
    val row = square / (NUM_BOARD_POKES + 1) - 1
    pokeHits[col]++
    colorHits[row]++
    if (pokeHits[col] >= NUM_BOARD_COLORS) hitFlags = hitFlags or (1 shl (col + 1))
    if (colorHits[row] >= NUM_BOARD_POKES) hitFlags = hitFlags or (1 shl ((row + 1) * 5))
    return square
  }

  companion object {
    const val BALLS_PER_ROUND = 6
    const val NUM_BOARD_COLORS = 3
    const val NUM_BOARD_POKES = 4
    const val NUM_ROULETTE_SLOTS = 12
    const val DEGREES_PER_SLOT = 30
    const val NUM_GRID_SELECTIONS = 19
    const val MAX_MULTIPLIER = 12

    val MULTIPLIERS = intArrayOf(0, 3, 4, 6, MAX_MULTIPLIER)
    val TABLE_MIN_BETS = intArrayOf(1, 3, 1, 6)

    /** sRouletteTables randDistanceLow / randDistanceHigh (left, right). */
    val ROULETTE_TABLES =
        arrayOf(DEGREES_PER_SLOT to DEGREES_PER_SLOT * 2, DEGREES_PER_SLOT / 2 to DEGREES_PER_SLOT)

    /** sRouletteSlots[].gridSquare, clockwise from slot 0. */
    val SLOT_SQUARES = intArrayOf(6, 12, 18, 9, 11, 17, 8, 14, 16, 7, 13, 19)
    val SLOT_FLAGS = IntArray(NUM_ROULETTE_SLOTS) { 1 shl SLOT_SQUARES[it] }

    val POKES = listOf("WYNAUT", "AZURILL", "SKITTY", "MAKUHITA")
    val COLORS = listOf("ORANGE", "GREEN", "PURPLE")

    /** IsHitInBetSelection. */
    fun isHit(square: Int, selection: Int): Boolean {
      val col = selection % (NUM_BOARD_POKES + 1)
      val row = selection / (NUM_BOARD_POKES + 1)
      return when {
        row == 0 -> square % 5 == col
        col == 0 -> square / 5 == row
        else -> square == selection
      }
    }
  }
}
