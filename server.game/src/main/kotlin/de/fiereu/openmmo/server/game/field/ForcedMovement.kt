package de.fiereu.openmmo.server.game.field

import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.common.enums.TileBehavior
import de.fiereu.openmmo.maps.MapDef
import de.fiereu.openmmo.net.game.packets.field.TravelMode

/** One step the game takes for the player, and how it looks (field_player_avatar.c). */
data class ForcedStep(val dir: Direction, val kind: Kind) {
  enum class Kind {
    /** PlayerWalkNormal: MB_WALK_*. */
    WALK,
    /** PlayerWalkFast / PlayerWalkFaster: currents, ice, muddy slopes. */
    FAST,
    /** PlayerSlide (MB_SLIDE_*) and the FRLG spinners: no walking frames. */
    SLIDE,
  }
}

/**
 * Gen 3 forced movement (TryDoMetatileBehaviorForcedMovement with sForcedMovementFuncs): after a
 * step lands on one of these tiles the game keeps moving the player until it stops on its own.
 * - ice / Trick House floor: slip on in the direction of travel until something blocks or the ice
 *   ends;
 * - MB_WALK_* one walking step that way, MB_SLIDE_* a slide that way (each repeats on the next such
 *   tile);
 * - water currents (surfing) and a waterfall without WATERFALL: carried along / pushed south;
 * - muddy slopes: slide back down unless on the Mach Bike;
 * - FRLG spinners (MB_SPIN_*): spin on, over ordinary floor too, turning on other spinners, until
 *   an MB_STOP_SPINNING tile or a wall;
 * - FRLG Cycling Road (MB_CYCLING_ROAD_PULL_DOWN): on the bike, pulled one tile south per step.
 *   Every forced step uses [FieldRules.step], so walls, elevation and NPCs stop it.
 */
object ForcedMovement {
  const val MAX_STEPS = 64

  /** The steps after the player stopped at ([x], [y]) having moved [moved]; empty when none. */
  fun path(
      map: MapDef,
      x: Int,
      y: Int,
      moved: Direction,
      z: Int,
      mode: TravelMode,
      obstacle: (Int, Int) -> FieldObstacle? = { _, _ -> null },
  ): List<ForcedStep> {
    val out = ArrayList<ForcedStep>()
    var cx = x
    var cy = y
    var cz = z
    var travel = moved
    var spin: Direction? = null
    while (out.size < MAX_STEPS) {
      val here = map.tileAt(cx, cy)?.behavior ?: TileBehavior.NORMAL
      val next: ForcedStep =
          when {
            here == TileBehavior.STOP_SPINNING -> null
            spinDir(here) != null ->
                ForcedStep(spinDir(here)!!, ForcedStep.Kind.SLIDE).also { spin = it.dir }
            spin != null -> ForcedStep(spin!!, ForcedStep.Kind.SLIDE)
            here == TileBehavior.ICE -> ForcedStep(travel, ForcedStep.Kind.FAST)
            walkDir(here) != null -> ForcedStep(walkDir(here)!!, ForcedStep.Kind.WALK)
            slideDir(here) != null -> ForcedStep(slideDir(here)!!, ForcedStep.Kind.SLIDE)
            here.isCurrent && (mode == TravelMode.SURF || mode == TravelMode.UNDERWATER) ->
                ForcedStep(currentDir(here)!!, ForcedStep.Kind.FAST)
            here == TileBehavior.WATERFALL && mode == TravelMode.SURF ->
                ForcedStep(Direction.DOWN, ForcedStep.Kind.FAST)
            here == TileBehavior.MUDDY_SLOPE && mode != TravelMode.MACH_BIKE ->
                ForcedStep(Direction.DOWN, ForcedStep.Kind.FAST)
            here == TileBehavior.CYCLING_ROAD_PULL_DOWN &&
                out.isEmpty() &&
                moved != Direction.UP &&
                (mode == TravelMode.MACH_BIKE || mode == TravelMode.ACRO_BIKE) ->
                ForcedStep(Direction.DOWN, ForcedStep.Kind.SLIDE)
            else -> null
          } ?: break
      val r = FieldRules.step(map, cx, cy, next.dir, cz, mode, obstacle = obstacle)
      val (nx, ny, nz) =
          when (r) {
            is StepOutcome.Walk -> Triple(r.x, r.y, r.elevation)
            else -> break
          }
      out += next
      cx = nx
      cy = ny
      cz = nz
      travel = next.dir
      // The Cycling Road pulls one tile per player step, not until the slope ends.
      if (here == TileBehavior.CYCLING_ROAD_PULL_DOWN) break
    }
    return out
  }

  private fun walkDir(b: TileBehavior): Direction? =
      when (b) {
        TileBehavior.WALK_EAST -> Direction.RIGHT
        TileBehavior.WALK_WEST -> Direction.LEFT
        TileBehavior.WALK_NORTH -> Direction.UP
        TileBehavior.WALK_SOUTH -> Direction.DOWN
        else -> null
      }

  private fun slideDir(b: TileBehavior): Direction? =
      when (b) {
        TileBehavior.SLIDE_EAST -> Direction.RIGHT
        TileBehavior.SLIDE_WEST -> Direction.LEFT
        TileBehavior.SLIDE_NORTH -> Direction.UP
        TileBehavior.SLIDE_SOUTH -> Direction.DOWN
        else -> null
      }

  private fun spinDir(b: TileBehavior): Direction? =
      when (b) {
        TileBehavior.SPIN_EAST -> Direction.RIGHT
        TileBehavior.SPIN_WEST -> Direction.LEFT
        TileBehavior.SPIN_NORTH -> Direction.UP
        TileBehavior.SPIN_SOUTH -> Direction.DOWN
        else -> null
      }

  private fun currentDir(b: TileBehavior): Direction? =
      when (b) {
        TileBehavior.CURRENT_EAST -> Direction.RIGHT
        TileBehavior.CURRENT_WEST -> Direction.LEFT
        TileBehavior.CURRENT_NORTH -> Direction.UP
        TileBehavior.CURRENT_SOUTH -> Direction.DOWN
        else -> null
      }

  /** Whether stepping onto [b] can start forced movement (the client stops predicting there). */
  fun isForcedTile(b: TileBehavior): Boolean =
      b == TileBehavior.ICE ||
          b.isCurrent ||
          walkDir(b) != null ||
          slideDir(b) != null ||
          spinDir(b) != null ||
          b == TileBehavior.MUDDY_SLOPE ||
          b == TileBehavior.CYCLING_ROAD_PULL_DOWN
}
