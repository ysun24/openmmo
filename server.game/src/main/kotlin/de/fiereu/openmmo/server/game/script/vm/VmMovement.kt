package de.fiereu.openmmo.server.game.script.vm

import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.server.game.script.MovementStep

/** One decomp movement action, reduced to what our clients can play. */
sealed interface MoveOp {
  data class Step(val step: MovementStep) : MoveOp

  /** face_player / face_away_player / face_original_direction, resolved when it runs. */
  data class FaceRelative(val kind: String) : MoveOp

  data class Emote(val kind: String) : MoveOp

  data object Show : MoveOp
}

/**
 * Maps the decomp's MOVEMENT_ACTION_* names (both games) onto the small step vocabulary the client
 * speaks: every walk speed becomes a normal or fast walk one tile, jumps become fast walks over the
 * tiles they cover, in-place animations become a turn, delays the two captured delay actions.
 * Purely cosmetic actions (affine anims, priorities, ground effects, the Acro bike's wheelies in
 * place) are dropped.
 */
object VmMovement {
  private fun dir(name: String): Direction? =
      when {
        name.endsWith("_DOWN") -> Direction.DOWN
        name.endsWith("_UP") -> Direction.UP
        name.endsWith("_LEFT") -> Direction.LEFT
        name.endsWith("_RIGHT") -> Direction.RIGHT
        else -> null
      }

  private fun face(d: Direction) =
      when (d) {
        Direction.DOWN -> MovementStep.FACE_DOWN
        Direction.UP -> MovementStep.FACE_UP
        Direction.LEFT -> MovementStep.FACE_LEFT
        else -> MovementStep.FACE_RIGHT
      }

  fun walk(d: Direction, fast: Boolean) =
      when (d) {
        Direction.DOWN -> if (fast) MovementStep.FAST_DOWN else MovementStep.WALK_DOWN
        Direction.UP -> if (fast) MovementStep.FAST_UP else MovementStep.WALK_UP
        Direction.LEFT -> if (fast) MovementStep.FAST_LEFT else MovementStep.WALK_LEFT
        else -> if (fast) MovementStep.FAST_RIGHT else MovementStep.WALK_RIGHT
      }

  fun faceStep(d: Direction) = face(d)

  /** The ops of one action named [action] (without `MOVEMENT_ACTION_`), or empty to skip it. */
  fun ops(action: String): List<MoveOp> {
    val a = action.removePrefix("MOVEMENT_ACTION_")
    val d = dir(a.removeSuffix("_FAST").removeSuffix("_SLOW"))
    fun steps(vararg s: MovementStep) = s.map { MoveOp.Step(it) }
    return when {
      a.startsWith("FACE_PLAYER") -> listOf(MoveOp.FaceRelative("player"))
      a.startsWith("FACE_AWAY_PLAYER") -> listOf(MoveOp.FaceRelative("away"))
      a.startsWith("FACE_ORIGINAL_DIRECTION") -> listOf(MoveOp.FaceRelative("original"))
      a == "SET_INVISIBLE" -> steps(MovementStep.SET_INVISIBLE)
      a == "SET_VISIBLE" -> listOf(MoveOp.Show)
      a.startsWith("EMOTE_") -> listOf(MoveOp.Emote(a.removePrefix("EMOTE_").lowercase()))
      a == "DELAY_16" -> steps(MovementStep.DELAY_16)
      a == "DELAY_8" -> steps(MovementStep.DELAY_8)
      a.startsWith("DELAY_") -> steps(MovementStep.DELAY_8)
      a.contains("DIAG_") -> {
        val vert = if (a.contains("NORTH")) Direction.UP else Direction.DOWN
        val horiz = if (a.contains("EAST")) Direction.RIGHT else Direction.LEFT
        steps(walk(vert, false), walk(horiz, false))
      }
      d == null -> emptyList()
      a.startsWith("FACE_") -> steps(face(d))
      a.startsWith("JUMP_2_") || a.startsWith("ACRO_WHEELIE_JUMP_") ->
          steps(walk(d, true), walk(d, true))
      a.startsWith("JUMP_IN_PLACE") || a.contains("IN_PLACE") -> {
        // walk_in_place_*: a turn; the fast left/right ones have their own captured actions.
        when {
          a.contains("IN_PLACE_FAST") && d == Direction.LEFT ->
              steps(MovementStep.WALK_IN_PLACE_FAST_LEFT)
          a.contains("IN_PLACE_FAST") && d == Direction.RIGHT ->
              steps(MovementStep.WALK_IN_PLACE_FAST_RIGHT)
          else -> steps(face(d))
        }
      }
      a.startsWith("JUMP_") || a.startsWith("JUMP_SPECIAL_") -> steps(walk(d, true))
      a.startsWith("WALK_SLOW") || a.startsWith("WALK_NORMAL") || a.startsWith("PLAYER_RUN") &&
          a.endsWith("_SLOW") -> steps(walk(d, false))
      a.startsWith("WALK_") && a.contains("AFFINE") -> steps(walk(d, false))
      a.startsWith("WALK_FAST") ||
          a.startsWith("PLAYER_RUN") ||
          a.startsWith("RIDE_WATER_CURRENT") ||
          a.startsWith("SLIDE_") ||
          a.startsWith("ACRO_") && a.contains("_MOVE_") ||
          a.startsWith("ACRO_WHEELIE_HOP_") && !a.contains("FACE") ||
          a.startsWith("ACRO_END_WHEELIE_MOVE") ||
          a.startsWith("FLY_") -> steps(walk(d, true))
      a.startsWith("WALK_") -> steps(walk(d, false))
      a.startsWith("ACRO_") -> steps(face(d))
      a.startsWith("START_ANIM_IN_DIRECTION") -> steps(face(d))
      else -> emptyList()
    }
  }
}
