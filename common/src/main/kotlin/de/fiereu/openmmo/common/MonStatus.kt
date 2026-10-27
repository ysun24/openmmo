package de.fiereu.openmmo.common

/**
 * The Gen 3 non-volatile status bits (`STATUS1_*` in the decomp's include/constants/battle.h). Sleep
 * keeps its remaining turns in the low three bits.
 */
object MonStatus {
  const val NONE = 0
  const val SLEEP = 0x7
  const val POISON = 1 shl 3
  const val BURN = 1 shl 4
  const val FREEZE = 1 shl 5
  const val PARALYSIS = 1 shl 6
  const val TOXIC = 1 shl 7
  const val ANY_POISON = POISON or TOXIC
  const val ANY = SLEEP or POISON or BURN or FREEZE or PARALYSIS or TOXIC

  /** The short label the games print beside a monster: SLP, PSN, BRN, FRZ, PAR; null if healthy. */
  fun label(status: Int): String? =
      when {
        status and SLEEP != 0 -> "SLP"
        status and ANY_POISON != 0 -> "PSN"
        status and BURN != 0 -> "BRN"
        status and FREEZE != 0 -> "FRZ"
        status and PARALYSIS != 0 -> "PAR"
        else -> null
      }

  /** The decomp's CalculatePPWithBonus: each bonus adds a fifth of the base PP. */
  fun maxPp(basePp: Int, ppBonuses: Int, slot: Int): Int {
    val bonus = (ppBonuses shr (slot * 2)) and 3
    return basePp + basePp * 20 * bonus / 100
  }
}
