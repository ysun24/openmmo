package de.fiereu.openmmo.common.enums

import java.io.File

/**
 * Maps the decomps' `MB_*` metatile behaviour names onto [TileBehavior]. FireRed and Emerald give
 * the same behaviour different numbers (and sometimes different names), so everything that reads
 * tileset attributes (the server's map codegen and the web asset exporter) classifies by name
 * through here and agrees on the result.
 */
object MetatileBehaviorNames {

  /** The [TileBehavior] of a decomp `MB_*` name, or null when we treat it as plain ground. */
  fun classify(name: String): TileBehavior? =
      when (name) {
        "MB_TALL_GRASS" -> TileBehavior.TALL_GRASS
        "MB_LONG_GRASS" -> TileBehavior.LONG_GRASS
        "MB_ASHGRASS" -> TileBehavior.ASH_GRASS
        "MB_JUMP_EAST" -> TileBehavior.JUMP_EAST
        "MB_JUMP_WEST" -> TileBehavior.JUMP_WEST
        "MB_JUMP_NORTH" -> TileBehavior.JUMP_NORTH
        "MB_JUMP_SOUTH" -> TileBehavior.JUMP_SOUTH
        // The only door the decomp opens with TryDoorWarp.
        "MB_ANIMATED_DOOR",
        "MB_WARP_DOOR" -> TileBehavior.DOOR
        "MB_NON_ANIMATED_DOOR",
        "MB_WATER_DOOR",
        "MB_CAVE_DOOR" -> TileBehavior.NON_ANIMATED_DOOR
        // The rest of what the decomp's IsWarpMetatileBehavior accepts, in both games.
        "MB_LADDER",
        "MB_UP_ESCALATOR",
        "MB_DOWN_ESCALATOR",
        "MB_REGULAR_WARP",
        "MB_FALL_WARP",
        "MB_DEEP_SOUTH_WARP",
        "MB_UNION_ROOM_WARP",
        "MB_BRIDGE_OVER_OCEAN",
        "MB_LAVARIDGE_GYM_B1F_WARP",
        "MB_LAVARIDGE_GYM_1F_WARP",
        "MB_LAVARIDGE_1F_WARP",
        "MB_AQUA_HIDEOUT_WARP",
        "MB_MT_PYRE_HOLE",
        "MB_MOSSDEEP_GYM_WARP" -> TileBehavior.LADDER
        "MB_UP_RIGHT_STAIR_WARP",
        "MB_DOWN_RIGHT_STAIR_WARP" -> TileBehavior.STAIR_WARP_EAST
        "MB_UP_LEFT_STAIR_WARP",
        "MB_DOWN_LEFT_STAIR_WARP" -> TileBehavior.STAIR_WARP_WEST
        "MB_NORTH_ARROW_WARP" -> TileBehavior.NORTH_ARROW_WARP
        "MB_SOUTH_ARROW_WARP",
        "MB_WATER_SOUTH_ARROW_WARP" -> TileBehavior.SOUTH_ARROW_WARP
        "MB_EAST_ARROW_WARP" -> TileBehavior.EAST_ARROW_WARP
        "MB_WEST_ARROW_WARP" -> TileBehavior.WEST_ARROW_WARP
        // MetatileBehavior_IsSurfableWaterOrUnderwater, split by what the games do with it:
        // encounters (sTileBitAttributes TILE_FLAG_HAS_ENCOUNTERS), the pond background, dive
        // spots, currents, and plain surfable water.
        "MB_POND_WATER" -> TileBehavior.POND_WATER
        "MB_OCEAN_WATER" -> TileBehavior.WATER
        "MB_SEMI_DEEP_WATER",
        "MB_FAST_WATER",
        "MB_NO_SURFACING",
        "MB_CYCLING_ROAD_WATER",
        "MB_UNDERWATER_BLOCKED_ABOVE" -> TileBehavior.SEMI_DEEP_WATER
        "MB_EASTWARD_CURRENT" -> TileBehavior.CURRENT_EAST
        "MB_WESTWARD_CURRENT" -> TileBehavior.CURRENT_WEST
        "MB_NORTHWARD_CURRENT" -> TileBehavior.CURRENT_NORTH
        "MB_SOUTHWARD_CURRENT" -> TileBehavior.CURRENT_SOUTH
        // field_player_avatar.c sForcedMovement*: ice, walk, slide and FRLG spinner tiles.
        "MB_ICE",
        "MB_TRICK_HOUSE_PUZZLE_8_FLOOR" -> TileBehavior.ICE
        "MB_WALK_EAST" -> TileBehavior.WALK_EAST
        "MB_WALK_WEST" -> TileBehavior.WALK_WEST
        "MB_WALK_NORTH" -> TileBehavior.WALK_NORTH
        "MB_WALK_SOUTH" -> TileBehavior.WALK_SOUTH
        "MB_SLIDE_EAST" -> TileBehavior.SLIDE_EAST
        "MB_SLIDE_WEST" -> TileBehavior.SLIDE_WEST
        "MB_SLIDE_NORTH" -> TileBehavior.SLIDE_NORTH
        "MB_SLIDE_SOUTH" -> TileBehavior.SLIDE_SOUTH
        "MB_SPIN_RIGHT" -> TileBehavior.SPIN_EAST
        "MB_SPIN_LEFT" -> TileBehavior.SPIN_WEST
        "MB_SPIN_UP" -> TileBehavior.SPIN_NORTH
        "MB_SPIN_DOWN" -> TileBehavior.SPIN_SOUTH
        "MB_STOP_SPINNING" -> TileBehavior.STOP_SPINNING
        "MB_MOUNTAIN_TOP" -> TileBehavior.MOUNTAIN
        "MB_DEEP_WATER",
        "MB_INTERIOR_DEEP_WATER",
        "MB_SOOTOPOLIS_DEEP_WATER" -> TileBehavior.DEEP_WATER
        "MB_WATERFALL" -> TileBehavior.WATERFALL
        "MB_SEAWEED",
        "MB_SEAWEED_NO_SURFACING" -> TileBehavior.SEAWEED
        "MB_PC",
        "MB_PLAYER_ROOM_PC_ON" -> TileBehavior.PC
        "MB_SAND",
        "MB_SAND_CAVE" -> TileBehavior.SAND
        "MB_FOOTPRINTS" -> TileBehavior.FOOTPRINTS
        "MB_DEEP_SAND" -> TileBehavior.DEEP_SAND
        "MB_PUDDLE" -> TileBehavior.PUDDLE
        "MB_SHALLOW_WATER" -> TileBehavior.SHALLOW_WATER
        "MB_MUDDY_SLOPE" -> TileBehavior.MUDDY_SLOPE
        "MB_BUMPY_SLOPE" -> TileBehavior.BUMPY_SLOPE
        "MB_ISOLATED_VERTICAL_RAIL",
        "MB_ISOLATED_HORIZONTAL_RAIL",
        "MB_VERTICAL_RAIL",
        "MB_HORIZONTAL_RAIL" -> TileBehavior.RAIL
        "MB_IMPASSABLE_EAST" -> TileBehavior.IMPASSABLE_EAST
        "MB_IMPASSABLE_WEST" -> TileBehavior.IMPASSABLE_WEST
        "MB_IMPASSABLE_NORTH" -> TileBehavior.IMPASSABLE_NORTH
        "MB_IMPASSABLE_SOUTH" -> TileBehavior.IMPASSABLE_SOUTH
        "MB_NO_RUNNING",
        "MB_RUNNING_DISALLOWED" -> TileBehavior.NO_RUNNING
        "MB_CYCLING_ROAD_PULL_DOWN",
        "MB_CYCLING_ROAD_PULL_DOWN_GRASS" -> TileBehavior.CYCLING_ROAD_PULL_DOWN
        "MB_BOOKSHELF",
        "MB_POKEMON_CENTER_BOOKSHELF",
        "MB_PICTURE_BOOK_SHELF",
        "MB_POKEMART_SHELF",
        "MB_SHOP_SHELF",
        "MB_TELEVISION",
        "MB_REGION_MAP" -> TileBehavior.READABLE
        "MB_CAVE" -> TileBehavior.CAVE
        "MB_INDOOR_ENCOUNTER" -> TileBehavior.INDOOR_ENCOUNTER
        else -> null
      }

  /**
   * The [TileBehavior] of one raw `metatile_attributes.bin` word. [attrWidth] 4 is FireRed, whose
   * words also carry a terrain type (bits 9-13, 2 = water) and an encounter type (bits 24-26, 1 =
   * land, 2 = water) that the game reads instead of the behaviour for surfing and encounters.
   */
  fun categorize(attr: Int, attrWidth: Int, ids: Map<Int, TileBehavior>): TileBehavior {
    val behaviorMask = if (attrWidth == 4) 0x1FF else 0xFF
    val named = ids[attr and behaviorMask]
    if (attrWidth != 4) return named ?: TileBehavior.NORMAL
    val terrain = (attr ushr 9) and 0x1F
    val encounter = (attr ushr 24) and 0x7
    // FireRed rolls encounters by the attribute's encounter type, not by the behaviour.
    return when {
      named != null && named.hasWaterEncounters && encounter != FRLG_ENCOUNTER_WATER ->
          TileBehavior.SEMI_DEEP_WATER
      named != null -> named
      encounter == FRLG_ENCOUNTER_WATER -> TileBehavior.WATER
      terrain == FRLG_TERRAIN_WATER -> TileBehavior.SEMI_DEEP_WATER
      encounter == FRLG_ENCOUNTER_LAND -> TileBehavior.LAND_ENCOUNTER
      else -> TileBehavior.NORMAL
    }
  }

  private const val FRLG_TERRAIN_WATER = 2
  private const val FRLG_ENCOUNTER_LAND = 1
  private const val FRLG_ENCOUNTER_WATER = 2

  /**
   * `MB_*` name -> numeric id from a decomp's `include/constants/metatile_behaviors.h`. FireRed
   * lists them as #defines, Emerald as an enum (with a stray #define or two); both are parsed.
   */
  fun parseIds(file: File): Map<String, Int> {
    if (!file.exists()) return emptyMap()
    val out = LinkedHashMap<String, Int>()
    val defineRe = Regex("""#define\s+(MB_\w+)\s+(0x[0-9A-Fa-f]+|\d+)""")
    for (m in defineRe.findAll(file.readText())) out[m.groupValues[1]] = parseInt(m.groupValues[2])
    val entryRe = Regex("""^(MB_\w+)\s*(?:=\s*(0x[0-9A-Fa-f]+|\d+))?""")
    var next = 0
    var inEnum = false
    for (raw in file.readLines()) {
      val line = raw.trim()
      if (line.startsWith("enum")) {
        inEnum = true
        continue
      }
      if (!inEnum) continue
      if (line.startsWith("}")) {
        inEnum = false
        continue
      }
      val m = entryRe.find(line) ?: continue
      val id = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { parseInt(it) } ?: next
      out.putIfAbsent(m.groupValues[1], id)
      next = id + 1
    }
    return out
  }

  /** Numeric behaviour id -> [TileBehavior] for one decomp, only the classified ones. */
  fun idTable(file: File): Map<Int, TileBehavior> =
      parseIds(file).mapNotNull { (name, id) -> classify(name)?.let { id to it } }.toMap()

  private fun parseInt(s: String): Int =
      if (s.startsWith("0x") || s.startsWith("0X")) s.substring(2).toInt(16) else s.toInt()
}
