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
  // Round 3 (battle + field, one list). Appended so the ordinals above never move; generated map
  // data and the browser's behaviour grids are regenerated from these names.
  /** Cave floor (MB_CAVE): land encounters, cave battle background. */
  CAVE,
  /** MB_INDOOR_ENCOUNTER: land encounters indoors (Abandoned Ship, FRLG towers). */
  INDOOR_ENCOUNTER,
  /** Deep sand (MB_DEEP_SAND): land encounters, footprints, the player sinks a little. */
  DEEP_SAND,
  /** Route 113's ash-covered grass (MB_ASHGRASS): land encounters. */
  ASH_GRASS,
  /** A FireRed tile whose metatile encounter type is TILE_ENCOUNTER_LAND. */
  LAND_ENCOUNTER,
  /** Sand that keeps footprints (MB_SAND, MB_SAND_CAVE): sand background, no encounters. */
  SAND,
  /** MB_MOUNTAIN_TOP: rock background. */
  MOUNTAIN,
  /** MB_POND_WATER: surfable, water encounters, pond battle background. */
  POND_WATER,
  /** Dive spots (MB_DEEP_WATER, MB_INTERIOR_DEEP_WATER, MB_SOOTOPOLIS_DEEP_WATER). */
  DEEP_WATER,
  /** Ocean water (MB_OCEAN_WATER, FRLG water-encounter tiles): surfable, water encounters. */
  WATER,
  WATERFALL,
  /** The Pokemon Center / bedroom PC. */
  PC,
  PUDDLE,
  SHALLOW_WATER,
  /** Only the Mach Bike climbs these, everything else slides down. */
  MUDDY_SLOPE,
  /**
   * Acro Bike terrain: bumpy slopes (bunny hop only, the bike stays on its back wheel) and rails
   * (MB_VERTICAL_RAIL; the horizontal and isolated kinds are appended at the end).
   */
  BUMPY_SLOPE,
  /** MB_VERTICAL_RAIL: ridden north/south only. */
  VERTICAL_RAIL,
  IMPASSABLE_EAST,
  IMPASSABLE_WEST,
  IMPASSABLE_NORTH,
  IMPASSABLE_SOUTH,
  /** Running (and FRLG's cycling) is not allowed on these tiles. */
  NO_RUNNING,
  /** Underwater weeds (MB_SEAWEED*): the underwater encounter tiles. */
  SEAWEED,
  /** FRLG Cycling Road slope that pulls the bike south. */
  CYCLING_ROAD_PULL_DOWN,
  /** Bookshelves, TVs, region maps and the like the player can read. */
  READABLE,
  /** Surfable water without encounters: semi-deep, fast, no-surfacing, cycling road water. */
  SEMI_DEEP_WATER,
  /** MB_FOOTPRINTS (Route 111 desert): footprints and land encounters, no sand background. */
  FOOTPRINTS,
  /** Water currents (MB_*WARD_CURRENT): surfable, push the player one tile per step. */
  CURRENT_EAST,
  CURRENT_WEST,
  CURRENT_NORTH,
  CURRENT_SOUTH,
  /** MB_ICE / MB_TRICK_HOUSE_PUZZLE_8_FLOOR: the player slides until something stops them. */
  ICE,
  /** MB_WALK_*: the player is walked one tile in that direction. */
  WALK_EAST,
  WALK_WEST,
  WALK_NORTH,
  WALK_SOUTH,
  /** MB_SLIDE_*: the player slides in that direction until stopped. */
  SLIDE_EAST,
  SLIDE_WEST,
  SLIDE_NORTH,
  SLIDE_SOUTH,
  /** FRLG spinner tiles (MB_SPIN_*): spin in that direction until a stop tile or a wall. */
  SPIN_EAST,
  SPIN_WEST,
  SPIN_NORTH,
  SPIN_SOUTH,
  /** MB_STOP_SPINNING ends a spin. */
  STOP_SPINNING,
  /** MB_HORIZONTAL_RAIL: ridden east/west only. */
  HORIZONTAL_RAIL,
  /** MB_ISOLATED_VERTICAL_RAIL: a single north/south rail, reached only by an Acro side jump. */
  ISOLATED_VERTICAL_RAIL,
  /** MB_ISOLATED_HORIZONTAL_RAIL: a single east/west rail, reached only by an Acro side jump. */
  ISOLATED_HORIZONTAL_RAIL;

  /** Any of the four Acro Bike rails. */
  val isRail: Boolean
    get() =
        this == VERTICAL_RAIL ||
            this == HORIZONTAL_RAIL ||
            this == ISOLATED_VERTICAL_RAIL ||
            this == ISOLATED_HORIZONTAL_RAIL

  /** The north/south rails (the bike cannot face east or west on them). */
  val isVerticalRail: Boolean
    get() = this == VERTICAL_RAIL || this == ISOLATED_VERTICAL_RAIL

  /** The east/west rails. */
  val isHorizontalRail: Boolean
    get() = this == HORIZONTAL_RAIL || this == ISOLATED_HORIZONTAL_RAIL

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
    get() =
        this == WATER ||
            this == POND_WATER ||
            this == DEEP_WATER ||
            this == SEMI_DEEP_WATER ||
            this == WATERFALL ||
            this == SEAWEED ||
            isCurrent

  /** The water currents. */
  val isCurrent: Boolean
    get() =
        this == CURRENT_EAST ||
            this == CURRENT_WEST ||
            this == CURRENT_NORTH ||
            this == CURRENT_SOUTH

  /** The grass the decomp rustles and meets land encounters in. */
  val isGrass: Boolean
    get() = this == TALL_GRASS || this == LONG_GRASS || this == ASH_GRASS

  /** MetatileBehavior_IsLandWildEncounter: TILE_FLAG_HAS_ENCOUNTERS on a non-water tile. */
  val hasLandEncounters: Boolean
    get() =
        isGrass ||
            this == CAVE ||
            this == INDOOR_ENCOUNTER ||
            this == DEEP_SAND ||
            this == FOOTPRINTS ||
            this == LAND_ENCOUNTER

  /** Same as [hasLandEncounters] (the field code's name). */
  val isLandEncounter: Boolean
    get() = hasLandEncounters

  /** MetatileBehavior_IsWaterWildEncounter: where surfing rolls the WATER table. */
  val hasWaterEncounters: Boolean
    get() = this == WATER || this == POND_WATER || this == DEEP_WATER || this == SEAWEED

  /** Surfable water with encounters (kept for the battle code's name). */
  val isSurfableWater: Boolean
    get() = hasWaterEncounters

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
