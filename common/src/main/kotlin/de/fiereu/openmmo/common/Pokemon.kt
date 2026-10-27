package de.fiereu.openmmo.common

import de.fiereu.openmmo.common.enums.EVs
import de.fiereu.openmmo.common.enums.IVs
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.common.enums.PokemonNature
import java.time.LocalDateTime

const val MAX_MOVE_SLOTS = 4

const val MAX_PARTY_SIZE = 6

data class Pokemon(
    val id: Long,
    val ownerId: Long,
    val container: PokemonContainer,
    val containerSlot: Short,
    val dexId: Int,
    val seed: Int,
    val ot: String,
    val nickname: String,
    val level: Byte,
    val hp: Short,
    val xp: Int,
    val eVs: EVs,
    val iVs: IVs,
    val moves: List<PokemonMove>,
    val isShiny: Boolean,
    val hasHiddenAbility: Boolean,
    val isAlpha: Boolean,
    val isSecret: Boolean,
    val isFatefulEncounter: Boolean,
    val isRaidEncounter: Boolean,
    val caughtAt: LocalDateTime,
    val isEgg: Boolean = false,
    /**
     * The persistent major status, packed the Gen 3 way (`status1` in the decomp): bits 0-2 are the
     * sleep turns left, then poison (0x08), burn (0x10), freeze (0x20), paralysis (0x40) and bad
     * poison (0x80). 0 is healthy. See [StatusCondition].
     */
    val status: Int = 0,
    /** Friendship (happiness) 0-255; friendship evolutions need 220. */
    val friendship: Int = DEFAULT_FRIENDSHIP,
    /** The held item's wire id, 0 for none. */
    val heldItem: Int = 0,
) {
  // seed is an unsigned 32-bit value on the wire, so mask before the modulo to avoid a negative
  // index when the high bit is set.
  val nature: PokemonNature =
      PokemonNature.entries[((seed.toLong() and 0xFFFFFFFFL) % PokemonNature.entries.size).toInt()]

  init {
    require(moves.size <= MAX_MOVE_SLOTS) { "A Pokemon can't have more than $MAX_MOVE_SLOTS moves" }
  }
}

data class PokemonMove(val id: Short, var pp: Byte)

const val DEFAULT_FRIENDSHIP = 70

/** The Gen 3 `status1` bits (include/constants/battle.h STATUS1_*). */
object StatusCondition {
  const val NONE = 0
  const val SLEEP_MASK = 0x07
  const val POISON = 0x08
  const val BURN = 0x10
  const val FREEZE = 0x20
  const val PARALYSIS = 0x40
  const val TOXIC = 0x80

  /** A short name for a packed status: "slp", "psn", "brn", "frz", "par", "tox" or null. */
  fun code(status: Int): String? =
      when {
        status and SLEEP_MASK != 0 -> "slp"
        status and TOXIC != 0 -> "tox"
        status and POISON != 0 -> "psn"
        status and BURN != 0 -> "brn"
        status and FREEZE != 0 -> "frz"
        status and PARALYSIS != 0 -> "par"
        else -> null
      }
}
