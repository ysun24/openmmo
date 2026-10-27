package de.fiereu.openmmo.common.enums

/**
 * What kind of tile the player is standing on, normalized across games from the decomp metatile
 * behaviors. Only the behaviors we act on are named, everything else is [NORMAL].
 */
enum class TileBehavior {
  NORMAL,
  TALL_GRASS,
  LONG_GRASS,
  JUMP_EAST,
  JUMP_WEST,
  JUMP_NORTH,
  JUMP_SOUTH,
  DOOR,
  /** Cave and water doors. They look like doors but warp like a ladders. */
  NON_ANIMATED_DOOR,
  /** Ladders, escalators and warp pads, which warp as soon as the player steps on them. */
  LADDER,
  STAIR_WARP_EAST,
  STAIR_WARP_WEST,
  /** Arrow warps are named after the direction the player walks to use them. */
  NORTH_ARROW_WARP,
  SOUTH_ARROW_WARP,
  EAST_ARROW_WARP,
  WEST_ARROW_WARP,
  // Field behaviours (round 3). Appended so the ordinals above, which generated map data and the
  // browser's behaviour grids store, never move.
  /** Surfable water that is not a dive spot (ponds, the sea, currents, fast water). */
  WATER,
  /** Surfable water the player can DIVE into (Emerald's deep water). */
  DEEP_WATER,
  WATERFALL,
  /** The Pokemon Center / bedroom PC. */
  PC,
  /** Sand that keeps footprints. */
  SAND,
  /** Deep sand: footprints, and the player sinks a little. */
  DEEP_SAND,
  PUDDLE,
  SHALLOW_WATER,
  /** Route 113's ash-covered grass. */
  ASH_GRASS,
  /** Only the Mach Bike climbs these, everything else slides down. */
  MUDDY_SLOPE,
  /** Acro Bike bunny-hop terrain: bumpy slopes and rails. */
  BUMPY_SLOPE,
  RAIL,
  IMPASSABLE_EAST,
  IMPASSABLE_WEST,
  IMPASSABLE_NORTH,
  IMPASSABLE_SOUTH,
  /** Running (and FRLG's cycling) is not allowed on these tiles. */
  NO_RUNNING,
  /** Underwater weeds: the underwater encounter tiles. */
  SEAWEED,
  /** FRLG Cycling Road slope that pulls the bike south. */
  CYCLING_ROAD_PULL_DOWN,
  /** Bookshelves, TVs, region maps and the like the player can read. */
  READABLE,
  /**
   * Plain ground that still meets land encounters: Emerald's MB_CAVE / MB_INDOOR_ENCOUNTER, and
   * FireRed tiles whose attribute encounter type is LAND.
   */
  ENCOUNTER_GROUND;

  /** The direction the player must walk while standing here to be warped. */
  val warpsWhenWalking: Direction?
    get() =
        when (this) {
          STAIR_WARP_EAST,
          EAST_ARROW_WARP -> Direction.RIGHT
          STAIR_WARP_WEST,
          WEST_ARROW_WARP -> Direction.LEFT
          NORTH_ARROW_WARP -> Direction.UP
          SOUTH_ARROW_WARP -> Direction.DOWN
          else -> null
        }

  val warpsOnStep: Boolean
    get() = this == LADDER || this == NON_ANIMATED_DOOR

  /** Water the player can SURF on (MetatileBehavior_IsSurfableWaterOrUnderwater). */
  val isSurfable: Boolean
    get() = this == WATER || this == DEEP_WATER || this == WATERFALL || this == SEAWEED

  /** The grass the decomp rustles and meets land encounters in. */
  val isGrass: Boolean
    get() = this == TALL_GRASS || this == LONG_GRASS || this == ASH_GRASS

  /** MetatileBehavior_IsLandWildEncounter. */
  val isLandEncounter: Boolean
    get() = isGrass || this == ENCOUNTER_GROUND || this == DEEP_SAND || this == SAND

  /** The direction a ledge on this tile is jumped in, or null when it is no ledge. */
  val ledgeDirection: Direction?
    get() =
        when (this) {
          JUMP_EAST -> Direction.RIGHT
          JUMP_WEST -> Direction.LEFT
          JUMP_NORTH -> Direction.UP
          JUMP_SOUTH -> Direction.DOWN
          else -> null
        }

  /**
   * IsMetatileDirectionallyImpassable: these tiles cannot be entered moving in [direction] (an
   * impassable-east tile blocks a step to the west into it, and so on).
   */
  fun blocksEntryMoving(direction: Direction): Boolean =
      when (this) {
        IMPASSABLE_EAST -> direction == Direction.LEFT
        IMPASSABLE_WEST -> direction == Direction.RIGHT
        IMPASSABLE_NORTH -> direction == Direction.DOWN
        IMPASSABLE_SOUTH -> direction == Direction.UP
        else -> false
      }

  /** The decomp also blocks leaving such a tile through its impassable side. */
  fun blocksExitMoving(direction: Direction): Boolean =
      when (this) {
        IMPASSABLE_EAST -> direction == Direction.RIGHT
        IMPASSABLE_WEST -> direction == Direction.LEFT
        IMPASSABLE_NORTH -> direction == Direction.UP
        IMPASSABLE_SOUTH -> direction == Direction.DOWN
        else -> false
      }
}
