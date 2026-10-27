package de.fiereu.openmmo.server.game.progression

import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.server.game.storage.CharacterStore
import de.fiereu.openmmo.server.game.storage.StoredCharacter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gym badges. They are the decomp's own story flags `FLAG_BADGE01_GET`..`FLAG_BADGE08_GET`, set by
 * the gym leader scripts, one set per region (`kanto/…`, `hoenn/…`). Other features ask here: the
 * field branch for HM use outside battle, the battle branch for obedience.
 */
@Singleton
class BadgeService @Inject constructor(private val characters: CharacterStore) {

  fun hasBadge(characterId: Long, region: Region, badge: Int): Boolean =
      characters.getCharacter(characterId)?.let { has(it, region, badge) } ?: false

  /** Bits 0-7 Kanto badges 1-8, bits 8-15 Hoenn badges 1-8. */
  fun mask(characterId: Long): Int = characters.getCharacter(characterId)?.let(::mask) ?: 0

  fun count(characterId: Long, region: Region): Int =
      (1..BADGES).count { hasBadge(characterId, region, it) }

  /**
   * Whether [moveId] may be used in the field in [region] (the decomp's field move checks): each HM
   * needs its region's badge. Moves that are no HM are not gated here.
   */
  fun canUseFieldMove(characterId: Long, region: Region, moveId: Int): Boolean {
    val badge = fieldMoveBadge(region, moveId) ?: return true
    return hasBadge(characterId, region, badge)
  }

  /**
   * The highest level a traded monster obeys at (IsMonDisobedient in battle_util.c): 10, raised to
   * 30/50/70 by badges 2/4/6 and to 100 by badge 8. Own monsters always obey.
   */
  fun obedienceLevel(characterId: Long, region: Region): Int {
    val s = characters.getCharacter(characterId) ?: return MIN_OBEDIENCE
    return obedienceLevel(s, region)
  }

  companion object {
    const val BADGES = 8
    const val MIN_OBEDIENCE = 10

    fun flag(region: Region, badge: Int): String =
        "${region.name.lowercase()}/FLAG_BADGE0${badge}_GET"

    fun has(s: StoredCharacter, region: Region, badge: Int): Boolean =
        flag(region, badge) in s.storyFlags

    fun mask(s: StoredCharacter): Int {
      var m = 0
      for (b in 1..BADGES) {
        if (has(s, Region.KANTO, b)) m = m or (1 shl (b - 1))
        if (has(s, Region.HOENN, b)) m = m or (1 shl (b - 1 + BADGES))
      }
      return m
    }

    fun obedienceLevel(s: StoredCharacter, region: Region): Int =
        when {
          has(s, region, 8) -> 100
          has(s, region, 6) -> 70
          has(s, region, 4) -> 50
          has(s, region, 2) -> 30
          else -> MIN_OBEDIENCE
        }

    // Move ids from the decomp's moves.h.
    private const val CUT = 15
    private const val FLY = 19
    private const val SURF = 57
    private const val STRENGTH = 70
    private const val FLASH = 148
    private const val ROCK_SMASH = 249
    private const val WATERFALL = 127
    private const val DIVE = 291

    /** Emerald: field_control_avatar / party_menu SetUpFieldMove_*; FireRed: its own order. */
    fun fieldMoveBadge(region: Region, moveId: Int): Int? =
        when (region) {
          Region.HOENN ->
              when (moveId) {
                CUT -> 1
                FLASH -> 2
                ROCK_SMASH -> 3
                STRENGTH -> 4
                SURF -> 5
                FLY -> 6
                DIVE -> 7
                WATERFALL -> 8
                else -> null
              }
          else ->
              when (moveId) {
                FLASH -> 1
                CUT -> 2
                FLY -> 3
                STRENGTH -> 4
                SURF -> 5
                ROCK_SMASH -> 6
                WATERFALL -> 7
                else -> null
              }
        }
  }
}
