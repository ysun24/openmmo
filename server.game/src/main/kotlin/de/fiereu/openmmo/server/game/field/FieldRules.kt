package de.fiereu.openmmo.server.game.field

import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.common.enums.TileBehavior
import de.fiereu.openmmo.maps.MapDef
import de.fiereu.openmmo.net.game.packets.field.TravelMode

/** What one player step does, decided by [FieldRules.step]. */
sealed interface StepOutcome {
  /** Refused: the client is snapped back to where it stood. */
  data object Blocked : StepOutcome

  /** An ordinary step onto (x, y), after which the player's elevation is [elevation]. */
  data class Walk(val x: Int, val y: Int, val elevation: Int) : StepOutcome

  /** A ledge hop: the player lands two tiles away. */
  data class Jump(val x: Int, val y: Int, val elevation: Int) : StepOutcome

  /** Surfing into a shore tile: the player hops off the water onto (x, y). */
  data class StopSurf(val x: Int, val y: Int, val elevation: Int) : StepOutcome

  /** STRENGTH: the boulder [localId] slides to (toX, toY); the player stays where it is. */
  data class PushBoulder(val localId: Int, val toX: Int, val toY: Int) : StepOutcome
}

/** A field object that blocks a tile on the server: a tree, a rock, a boulder, an item ball. */
data class FieldObstacle(val localId: Int, val kind: Kind) {
  enum class Kind {
    CUT_TREE,
    ROCK_SMASH_ROCK,
    BOULDER,
    ITEM_BALL,
    /** Any other npc the server keeps solid (trainers, static people). */
    NPC,
  }
}

/**
 * The Gen 3 step rules (field_player_avatar.c CheckForObjectEventCollision and
 * event_object_movement.c GetCollisionAtCoords), pure so they can be tested and mirrored by the
 * browser's prediction. Elevation follows ObjectEventUpdateElevation: 0 and 15 tiles are wildcards,
 * 15 (bridges) keeps the old elevation.
 */
object FieldRules {
  /** ELEVATION_DEFAULT: land. */
  const val LAND = 3
  /** The elevation of water tiles, and of a surfing player. */
  const val WATER = 1
  const val ANY = 0
  const val BRIDGE = 15

  /** IsElevationMismatchAt: an object at [z] cannot enter a tile of [tileZ]. */
  fun elevationMismatch(z: Int, tileZ: Int): Boolean =
      z != ANY && tileZ != ANY && tileZ != BRIDGE && tileZ != z

  /** ObjectEventUpdateElevation after a step from a tile of [fromZ] onto one of [toZ]. */
  fun elevationAfter(current: Int, fromZ: Int, toZ: Int): Int =
      if (toZ == BRIDGE || fromZ == BRIDGE) current else toZ

  /** Where a player arriving at (x, y) by warp or login stands. */
  fun arrivalElevation(map: MapDef, x: Int, y: Int, mode: TravelMode): Int {
    val z = map.tileAt(x, y)?.elevation ?: LAND
    return if (z == BRIDGE) (if (mode == TravelMode.SURF) WATER else LAND) else z
  }

  /**
   * One step from ([fromX], [fromY]) in [dir] for a player at elevation [z] travelling as [mode].
   * [obstacle] says what blocks a tile, [strength] whether boulders can be pushed. Edges of the map
   * are not handled here (the caller crosses connections first).
   */
  fun step(
      map: MapDef,
      fromX: Int,
      fromY: Int,
      dir: Direction,
      z: Int,
      mode: TravelMode,
      strength: Boolean = false,
      obstacle: (Int, Int) -> FieldObstacle? = { _, _ -> null },
  ): StepOutcome {
    if (dir.dx == 0 && dir.dy == 0) return StepOutcome.Blocked
    val toX = fromX + dir.dx
    val toY = fromY + dir.dy
    val from = map.tileAt(fromX, fromY)
    val to = map.tileAt(toX, toY) ?: return StepOutcome.Blocked
    val fromBehavior = from?.behavior ?: TileBehavior.NORMAL

    // Ledges: the tile ahead is a ledge facing our way. Not while surfing (no ledges in water).
    if (mode != TravelMode.SURF &&
        mode != TravelMode.UNDERWATER &&
        to.behavior.ledgeDirection == dir) {
      val lx = fromX + dir.dx * 2
      val ly = fromY + dir.dy * 2
      val land = map.tileAt(lx, ly) ?: return StepOutcome.Blocked
      if (land.blocksMovement() || obstacle(lx, ly) != null) return StepOutcome.Blocked
      return StepOutcome.Jump(lx, ly, elevationAfter(z, from?.elevation ?: z, land.elevation))
    }

    if (to.blocksMovement() ||
        to.behavior.blocksEntryMoving(dir) ||
        fromBehavior.blocksExitMoving(dir)) {
      return StepOutcome.Blocked
    }

    val mismatch = elevationMismatch(z, to.elevation)
    val obj = obstacle(toX, toY)
    if (mismatch) {
      // CanStopSurfing: from water onto default-elevation land with nothing on it.
      return if (mode == TravelMode.SURF && to.elevation == LAND && obj == null)
          StepOutcome.StopSurf(toX, toY, LAND)
      else StepOutcome.Blocked
    }
    if (obj != null) {
      if (obj.kind == FieldObstacle.Kind.BOULDER && strength && mode == TravelMode.WALK) {
        val bx = toX + dir.dx
        val by = toY + dir.dy
        val beyond = map.tileAt(bx, by) ?: return StepOutcome.Blocked
        // TryPushBoulder: the boulder needs a free, same-elevation tile that is no door.
        if (!beyond.blocksMovement() &&
            !elevationMismatch(to.elevation, beyond.elevation) &&
            obstacle(bx, by) == null &&
            beyond.behavior != TileBehavior.NON_ANIMATED_DOOR &&
            beyond.behavior != TileBehavior.DOOR) {
          return StepOutcome.PushBoulder(obj.localId, bx, by)
        }
      }
      return StepOutcome.Blocked
    }

    // Bike terrain (CheckAcroBikeCollision and the Mach Bike's muddy slopes).
    when (to.behavior) {
      TileBehavior.MUDDY_SLOPE ->
          if (dir == Direction.UP && mode != TravelMode.MACH_BIKE) return StepOutcome.Blocked
      TileBehavior.BUMPY_SLOPE,
      TileBehavior.RAIL -> if (mode != TravelMode.ACRO_BIKE) return StepOutcome.Blocked
      else -> {}
    }

    return StepOutcome.Walk(toX, toY, elevationAfter(z, from?.elevation ?: z, to.elevation))
  }

  /**
   * IsPlayerFacingSurfableFishableWater: on land at the default elevation, facing a water tile the
   * player cannot walk onto.
   */
  fun canStartSurf(
      map: MapDef,
      x: Int,
      y: Int,
      facing: Direction,
      z: Int,
      mode: TravelMode
  ): Boolean {
    if (mode == TravelMode.SURF || mode == TravelMode.UNDERWATER) return false
    if (z != LAND) return false
    val t = map.tileAt(x + facing.dx, y + facing.dy) ?: return false
    return t.behavior.isSurfable &&
        t.behavior != TileBehavior.WATERFALL &&
        !t.blocksMovement() &&
        elevationMismatch(z, t.elevation)
  }

  /** The tiles WATERFALL climbs from (x, y): straight up while the tile is a waterfall. */
  fun waterfallClimb(map: MapDef, x: Int, y: Int): Pair<Int, Int>? {
    var cy = y - 1
    if (map.tileAt(x, cy)?.behavior != TileBehavior.WATERFALL) return null
    while (map.tileAt(x, cy)?.behavior == TileBehavior.WATERFALL) cy--
    val top = map.tileAt(x, cy) ?: return null
    if (top.blocksMovement()) return null
    return x to cy
  }

  /** Whether the bicycle may come out here (map header allow_cycling, not on water). */
  fun canRide(map: MapDef, x: Int, y: Int, mode: TravelMode): Boolean =
      map.allowCycling &&
          mode != TravelMode.SURF &&
          mode != TravelMode.UNDERWATER &&
          map.tileAt(x, y)?.behavior?.isSurfable != true

  /** Running shoes work on maps that allow running, on tiles that do not forbid it. */
  fun canRun(map: MapDef, x: Int, y: Int): Boolean =
      map.allowRunning && map.tileAt(x, y)?.behavior != TileBehavior.NO_RUNNING
}
