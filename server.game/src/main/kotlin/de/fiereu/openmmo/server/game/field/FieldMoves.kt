package de.fiereu.openmmo.server.game.field

import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.net.game.packets.field.FieldAction

/**
 * Which move each field action needs and which badge unlocks it, per game (party_menu.c
 * CursorCB_FieldMove: an HM needs `FLAG_BADGE01_GET + fieldMove`, in each game's own field move
 * order). FireRed: Flash 1, Cut 2, Fly 3, Strength 4, Surf 5, Rock Smash 6, Waterfall 7. Emerald:
 * Cut 1, Flash 2, Rock Smash 3, Strength 4, Surf 5, Fly 6, Dive 7, Waterfall 8.
 */
object FieldMoves {
  // National move ids.
  const val CUT = 15
  const val FLY = 19
  const val SURF = 57
  const val STRENGTH = 70
  const val DIG = 91
  const val TELEPORT = 100
  const val WATERFALL = 127
  const val FLASH = 148
  const val SWEET_SCENT = 230
  const val ROCK_SMASH = 249
  const val DIVE = 291

  val moveOf: Map<FieldAction, Int> =
      mapOf(
          FieldAction.CUT to CUT,
          FieldAction.FLY to FLY,
          FieldAction.SURF to SURF,
          FieldAction.STRENGTH to STRENGTH,
          FieldAction.DIG to DIG,
          FieldAction.TELEPORT to TELEPORT,
          FieldAction.WATERFALL to WATERFALL,
          FieldAction.FLASH to FLASH,
          FieldAction.SWEET_SCENT to SWEET_SCENT,
          FieldAction.ROCK_SMASH to ROCK_SMASH,
          FieldAction.DIVE to DIVE,
      )

  private val kantoBadge =
      mapOf(
          FieldAction.FLASH to 1,
          FieldAction.CUT to 2,
          FieldAction.FLY to 3,
          FieldAction.STRENGTH to 4,
          FieldAction.SURF to 5,
          FieldAction.ROCK_SMASH to 6,
          FieldAction.WATERFALL to 7,
      )
  private val hoennBadge =
      mapOf(
          FieldAction.CUT to 1,
          FieldAction.FLASH to 2,
          FieldAction.ROCK_SMASH to 3,
          FieldAction.STRENGTH to 4,
          FieldAction.SURF to 5,
          FieldAction.FLY to 6,
          FieldAction.DIVE to 7,
          FieldAction.WATERFALL to 8,
      )

  /** The badge number (1-8) [action] needs in [region], or null when it needs none. */
  fun badgeFor(region: Region, action: FieldAction): Int? =
      (if (region == Region.HOENN) hoennBadge else kantoBadge)[action]

  /** Dive does not exist in FireRed. */
  fun existsIn(region: Region, action: FieldAction): Boolean =
      action != FieldAction.DIVE || region == Region.HOENN

  /** The story flag a badge sets: `<region>/FLAG_BADGE0n_GET`. */
  fun badgeFlag(region: Region, badge: Int): String =
      "${region.storyName}/FLAG_BADGE0${badge}_GET"

  /**
   * checkpartymove: the first party monster (in slot order, not an egg) that knows [moveId], or
   * null. Fainted monsters may use field moves in Gen 3.
   */
  fun partyMonWith(party: List<Pokemon>, moveId: Int): Pokemon? =
      party
          .filter { !it.isEgg }
          .sortedBy { it.containerSlot }
          .firstOrNull { mon -> mon.moves.any { it.id.toInt() == moveId } }

  /**
   * Whether [action] passes the badge and party checks, given the player's [flags]. Not where it
   * is used: [FieldService] checks the tile.
   */
  fun usable(region: Region, action: FieldAction, party: List<Pokemon>, flags: Set<String>): Boolean {
    if (!existsIn(region, action)) return false
    val move = moveOf[action] ?: return false
    if (partyMonWith(party, move) == null) return false
    val badge = badgeFor(region, action) ?: return true
    return badgeFlag(region, badge) in flags
  }
}

/** The namespace story flags and vars use for a region ("kanto", "hoenn"). */
val Region.storyName: String
  get() = if (this == Region.HOENN) "hoenn" else "kanto"
