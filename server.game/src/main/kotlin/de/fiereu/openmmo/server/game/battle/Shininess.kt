package de.fiereu.openmmo.server.game.battle

/**
 * Gen 3 shininess (`IsShinyOtIdPersonality` in pokeemerald/pokefirered `src/pokemon.c`):
 *
 *     shinyValue = HIHALF(otId) ^ LOHALF(otId) ^ HIHALF(personality) ^ LOHALF(personality)
 *     shiny      = shinyValue < SHINY_ODDS (8)          -> 8 / 65536 = 1 in 8192
 *
 * [de.fiereu.openmmo.common.Pokemon.seed] is the personality value (the nature is `seed % 25`,
 * exactly like the GBA's `GetNatureFromPersonality`). Characters have no trainer id of their own,
 * so the 32-bit OT id (TID low half, SID high half, as `gSaveBlock2Ptr->playerTrainerId`) is
 * derived from the character id: stable per character, uniformly spread.
 *
 * Who it applies to, as in `CreateMon`: wild and gift monsters use the player's OT id
 * (`OT_ID_PLAYER_ID`), trainer monsters are never shiny (`OT_ID_RANDOM_NO_SHINY`).
 */
object Shininess {
  const val SHINY_ODDS = 8

  fun isShiny(personality: Int, otId: Int): Boolean {
    val v =
        (otId ushr 16) xor (otId and 0xFFFF) xor (personality ushr 16) xor (personality and 0xFFFF)
    return v < SHINY_ODDS
  }

  /** The character's 32-bit OT id (splitmix64 finaliser of the id: stable, well spread). */
  fun otId(characterId: Long): Int {
    var z = characterId + -0x61c8864680b583ebL
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    return (z xor (z ushr 31)).toInt()
  }

  /** Trainer id (the low half, the number the GBA shows on the trainer card). */
  fun trainerId(characterId: Long): Int = otId(characterId) and 0xFFFF

  /**
   * A personality value that is shiny for [otId], keeping the nature of [nature] (`pid % 25`):
   * picks the low half freely and solves the high half, like a forced-shiny `CreateMon` would.
   */
  fun shinyPersonality(otId: Int, nature: Int, random: java.util.Random): Int {
    while (true) {
      val lo = random.nextInt(0x10000)
      val hi = (otId ushr 16) xor (otId and 0xFFFF) xor lo xor random.nextInt(SHINY_ODDS)
      val pid = (hi shl 16) or lo
      if (((pid.toLong() and 0xFFFFFFFFL) % 25).toInt() == nature) return pid
    }
  }
}
