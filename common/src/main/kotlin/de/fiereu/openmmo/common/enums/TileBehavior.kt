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
  // r3-battle: appended (ordinals are stored in map data). Wild encounter and battle background
  // tiles, from the decomp's TILE_FLAG_HAS_ENCOUNTERS (Emerald) / encounter type bits (FireRed).
  /** Cave floor (MB_CAVE): land encounters, cave background. */
  CAVE,
  /** MB_INDOOR_ENCOUNTER: land encounters indoors (Hoenn's Abandoned Ship, FRLG towers). */
  INDOOR_ENCOUNTER,
  /** MB_DEEP_SAND, MB_FOOTPRINTS (desert): land encounters, sand background. */
  DEEP_SAND,
  /** MB_ASHGRASS (Route 113): land encounters. */
  ASH_GRASS,
  /** A FireRed tile whose metatile encounter type is TILE_ENCOUNTER_LAND. */
  LAND_ENCOUNTER,
  /** MB_SAND: sand background, no encounters. */
  SAND,
  /** MB_MOUNTAIN_TOP: rock background. */
  MOUNTAIN,
  /** Surfable shallow/pond water (MB_POND_WATER, seaweed...): water encounters while surfing. */
  POND_WATER,
  /** MB_DEEP_WATER / MB_OCEAN_WATER / interior deep water: water encounters while surfing. */
  DEEP_WATER;

  /** True where the GBA rolls the LAND table on a step. */
  val hasLandEncounters: Boolean
    get() =
        this == TALL_GRASS ||
            this == LONG_GRASS ||
            this == CAVE ||
            this == INDOOR_ENCOUNTER ||
            this == DEEP_SAND ||
            this == ASH_GRASS ||
            this == LAND_ENCOUNTER

  /** Surfable water, where the GBA rolls the WATER table while surfing. */
  val isSurfableWater: Boolean
    get() = this == POND_WATER || this == DEEP_WATER

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
}
