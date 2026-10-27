package de.fiereu.openmmo.server.game.field

import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.common.enums.TileBehavior
import de.fiereu.openmmo.maps.MapDef
import de.fiereu.openmmo.net.game.packets.field.TravelMode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.Base64

private fun grid(vararg rows: String): MapDef {
  val legend =
      mapOf(
          '.' to (false to (3 to TileBehavior.NORMAL)),
          '#' to (true to (3 to TileBehavior.NORMAL)),
          'i' to (false to (3 to TileBehavior.ICE)),
          '~' to (false to (1 to TileBehavior.WATER)),
          '>' to (false to (1 to TileBehavior.CURRENT_EAST)),
          'm' to (false to (3 to TileBehavior.MUDDY_SLOPE)),
          'R' to (false to (3 to TileBehavior.SPIN_EAST)),
          'D' to (false to (3 to TileBehavior.SPIN_SOUTH)),
          'S' to (false to (3 to TileBehavior.STOP_SPINNING)),
          'w' to (false to (3 to TileBehavior.WALK_SOUTH)),
          'c' to (false to (3 to TileBehavior.CYCLING_ROAD_PULL_DOWN)),
      )
  val h = rows.size
  val w = rows[0].length
  val blocks = ByteArray(w * h * 2)
  val beh = ByteArray(w * h)
  for (y in 0 until h) for (x in 0 until w) {
    val (blocked, rest) = legend.getValue(rows[y][x])
    val (z, b) = rest
    val raw = ((if (blocked) 1 else 0) shl 10) or (z shl 12)
    blocks[(y * w + x) * 2] = (raw and 0xFF).toByte()
    blocks[(y * w + x) * 2 + 1] = (raw shr 8).toByte()
    beh[y * w + x] = b.ordinal.toByte()
  }
  return MapDef(
      regionId = 0,
      bankId = 3,
      mapId = 0,
      width = w,
      height = h,
      blockData = Base64.getEncoder().encodeToString(blocks),
      behaviorData = Base64.getEncoder().encodeToString(beh),
      mapTypeName = "MAP_TYPE_ROUTE",
  )
}

private fun dirs(p: List<ForcedStep>) = p.map { it.dir }

/** field_player_avatar.c forced movement, as [ForcedMovement.path] plays it. */
class ForcedMovementTest :
    FunSpec({
      test("ice: slip on in the direction of travel until a wall or the end of the ice") {
        val m = grid("..iii#", "......")
        dirs(ForcedMovement.path(m, 2, 0, Direction.RIGHT, 3, TravelMode.WALK)) shouldBe
            listOf(Direction.RIGHT, Direction.RIGHT)
        val m2 = grid("..ii..")
        // Slides onto the first plain tile and stops there.
        dirs(ForcedMovement.path(m2, 2, 0, Direction.RIGHT, 3, TravelMode.WALK)) shouldBe
            listOf(Direction.RIGHT, Direction.RIGHT)
        ForcedMovement.path(m2, 1, 0, Direction.RIGHT, 3, TravelMode.WALK) shouldBe emptyList()
      }

      test("a current carries a surfer along, not a walker") {
        val m = grid("~>>>~~")
        dirs(ForcedMovement.path(m, 1, 0, Direction.RIGHT, 1, TravelMode.SURF)) shouldBe
            List(3) { Direction.RIGHT }
        ForcedMovement.path(m, 1, 0, Direction.RIGHT, 1, TravelMode.WALK) shouldBe emptyList()
      }

      test("FRLG spinners spin over plain floor until a stop tile; another spinner turns") {
        val m = grid("R...D.", ".....#", "....S.")
        dirs(ForcedMovement.path(m, 0, 0, Direction.RIGHT, 3, TravelMode.WALK)) shouldBe
            listOf(
                Direction.RIGHT,
                Direction.RIGHT,
                Direction.RIGHT,
                Direction.RIGHT,
                Direction.DOWN,
                Direction.DOWN)
      }

      test("a muddy slope slides back down unless on the Mach Bike") {
        val m = grid(".", "m", "m", ".")
        dirs(ForcedMovement.path(m, 0, 1, Direction.UP, 3, TravelMode.WALK)) shouldBe
            listOf(Direction.DOWN, Direction.DOWN)
        ForcedMovement.path(m, 0, 1, Direction.UP, 3, TravelMode.MACH_BIKE) shouldBe emptyList()
      }

      test("walk tiles take one walking step; the Cycling Road pulls a bike one tile") {
        val m = grid("w", ".", ".")
        ForcedMovement.path(m, 0, 0, Direction.DOWN, 3, TravelMode.WALK) shouldBe
            listOf(ForcedStep(Direction.DOWN, ForcedStep.Kind.WALK))
        val road = grid("c", "c", "c", ".")
        dirs(ForcedMovement.path(road, 0, 1, Direction.LEFT, 3, TravelMode.MACH_BIKE)) shouldBe
            listOf(Direction.DOWN)
        ForcedMovement.path(road, 0, 1, Direction.UP, 3, TravelMode.MACH_BIKE) shouldBe emptyList()
        ForcedMovement.path(road, 0, 1, Direction.LEFT, 3, TravelMode.WALK) shouldBe emptyList()
      }
    })
