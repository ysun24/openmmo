package de.fiereu.openmmo.server.game.field

import de.fiereu.openmmo.common.enums.CharacterGender
import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.common.enums.MovementType
import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.common.enums.TileBehavior
import de.fiereu.openmmo.maps.MapDef
import de.fiereu.openmmo.maps.MapManager
import de.fiereu.openmmo.maps.NpcDef
import de.fiereu.openmmo.maps.TrainerSpot
import de.fiereu.openmmo.net.game.packets.field.FieldAction
import de.fiereu.openmmo.net.game.packets.field.TravelMode
import de.fiereu.openmmo.server.game.storage.CharacterStore
import de.fiereu.openmmo.server.game.storage.EntityIdService
import de.fiereu.openmmo.server.game.testsupport.FakeCharacterRepository
import de.fiereu.openmmo.server.game.testsupport.FakeSession
import de.fiereu.openmmo.server.game.testsupport.fieldService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.Base64
import kotlinx.coroutines.test.runTest

/** One tile of a synthetic map: blocked, elevation, behaviour. */
private data class T(
    val blocked: Boolean = false,
    val z: Int = 3,
    val b: TileBehavior = TileBehavior.NORMAL,
)

/** Builds a map from rows of tile codes: '.' land, '#' wall, '~' water, 'v' south ledge, ... */
private fun map(
    vararg rows: String,
    npcs: List<NpcDef> = emptyList(),
    cycling: Boolean = true,
): MapDef {
  val legend =
      mapOf(
          '.' to T(),
          '#' to T(blocked = true),
          '~' to T(z = 1, b = TileBehavior.WATER),
          '=' to T(z = 1, b = TileBehavior.DEEP_WATER),
          'W' to T(z = 1, b = TileBehavior.WATERFALL),
          'v' to T(blocked = true, z = 0, b = TileBehavior.JUMP_SOUTH),
          '>' to T(blocked = true, z = 0, b = TileBehavior.JUMP_EAST),
          '0' to T(z = 0),
          'B' to T(z = 15),
          'm' to T(b = TileBehavior.MUDDY_SLOPE),
          'r' to T(b = TileBehavior.RAIL),
          'e' to T(b = TileBehavior.IMPASSABLE_EAST),
          '"' to T(b = TileBehavior.TALL_GRASS),
      )
  val h = rows.size
  val w = rows[0].length
  val blocks = ByteArray(w * h * 2)
  val beh = ByteArray(w * h)
  for (y in 0 until h) for (x in 0 until w) {
    val t = legend.getValue(rows[y][x])
    val raw = ((if (t.blocked) 1 else 0) shl 10) or (t.z shl 12)
    blocks[(y * w + x) * 2] = (raw and 0xFF).toByte()
    blocks[(y * w + x) * 2 + 1] = (raw shr 8).toByte()
    beh[y * w + x] = t.b.ordinal.toByte()
  }
  return MapDef(
      regionId = 0,
      bankId = 3,
      mapId = 0,
      width = w,
      height = h,
      blockData = Base64.getEncoder().encodeToString(blocks),
      behaviorData = Base64.getEncoder().encodeToString(beh),
      npcs = npcs,
      allowCycling = cycling,
      mapTypeName = "MAP_TYPE_ROUTE",
      name = "TestMap",
  )
}

class FieldRulesTest :
    FunSpec({
      test("land cannot walk onto water; a surfer paddles on and hops off at the shore") {
        val m = map(".~~", ".~~")
        FieldRules.step(m, 0, 0, Direction.RIGHT, 3, TravelMode.WALK) shouldBe StepOutcome.Blocked
        FieldRules.step(m, 1, 0, Direction.RIGHT, 1, TravelMode.SURF) shouldBe
            StepOutcome.Walk(2, 0, 1)
        FieldRules.step(m, 1, 0, Direction.LEFT, 1, TravelMode.SURF) shouldBe
            StepOutcome.StopSurf(0, 0, 3)
      }

      test("a shore tile with an object on it does not end the surf") {
        val m = map(".~")
        val obstacle = FieldObstacle(4, FieldObstacle.Kind.NPC)
        FieldRules.step(m, 1, 0, Direction.LEFT, 1, TravelMode.SURF) { x, _ ->
          if (x == 0) obstacle else null
        } shouldBe StepOutcome.Blocked
      }

      test("ledges hop two tiles in their direction only, and not while surfing") {
        val m = map(".", "v", ".", ".")
        FieldRules.step(m, 0, 0, Direction.DOWN, 3, TravelMode.WALK) shouldBe
            StepOutcome.Jump(0, 2, 3)
        FieldRules.step(m, 0, 2, Direction.UP, 3, TravelMode.WALK) shouldBe StepOutcome.Blocked
        val side = map("...", ">..")
        // walking along a ledge row: the ledge tile itself is solid
        FieldRules.step(side, 1, 0, Direction.DOWN, 3, TravelMode.WALK) shouldBe
            StepOutcome.Walk(1, 1, 3)
        FieldRules.step(side, 0, 0, Direction.DOWN, 3, TravelMode.WALK) shouldBe StepOutcome.Blocked
        FieldRules.step(side, 1, 1, Direction.LEFT, 3, TravelMode.WALK) shouldBe StepOutcome.Blocked
      }

      test("a ledge whose landing is blocked or occupied refuses the hop") {
        val m = map(".", "v", "#")
        FieldRules.step(m, 0, 0, Direction.DOWN, 3, TravelMode.WALK) shouldBe StepOutcome.Blocked
      }

      test("elevation 0 and bridges are wildcards; a bridge keeps the walker's elevation") {
        FieldRules.elevationMismatch(3, 0) shouldBe false
        FieldRules.elevationMismatch(3, 15) shouldBe false
        FieldRules.elevationMismatch(0, 1) shouldBe false
        FieldRules.elevationMismatch(3, 4) shouldBe true
        FieldRules.elevationAfter(3, 3, 15) shouldBe 3
        FieldRules.elevationAfter(3, 15, 4) shouldBe 3
        FieldRules.elevationAfter(3, 3, 0) shouldBe 0
        val m = map(".0~")
        // standing on a 0 tile the next step onto water is allowed (z becomes 0)
        FieldRules.step(m, 1, 0, Direction.RIGHT, 0, TravelMode.WALK) shouldBe
            StepOutcome.Walk(2, 0, 1)
      }

      test("STRENGTH pushes a boulder onto a free tile; without it the boulder is a wall") {
        val m = map("....")
        val boulder = FieldObstacle(7, FieldObstacle.Kind.BOULDER)
        val at: (Int, Int) -> FieldObstacle? = { x, _ -> if (x == 1) boulder else null }
        FieldRules.step(
            m, 0, 0, Direction.RIGHT, 3, TravelMode.WALK, strength = false, obstacle = at) shouldBe
            StepOutcome.Blocked
        FieldRules.step(
            m, 0, 0, Direction.RIGHT, 3, TravelMode.WALK, strength = true, obstacle = at) shouldBe
            StepOutcome.PushBoulder(7, 2, 0)
        val wall = map("..#")
        FieldRules.step(
            wall,
            0,
            0,
            Direction.RIGHT,
            3,
            TravelMode.WALK,
            strength = true,
            obstacle = at) shouldBe StepOutcome.Blocked
      }

      test("muddy slopes need the Mach Bike uphill, rails the Acro Bike") {
        val m = map(".", "m", ".")
        FieldRules.step(m, 0, 2, Direction.UP, 3, TravelMode.WALK) shouldBe StepOutcome.Blocked
        FieldRules.step(m, 0, 2, Direction.UP, 3, TravelMode.MACH_BIKE) shouldBe
            StepOutcome.Walk(0, 1, 3)
        FieldRules.step(m, 0, 0, Direction.DOWN, 3, TravelMode.WALK) shouldBe
            StepOutcome.Walk(0, 1, 3)
        val r = map(".r")
        FieldRules.step(r, 0, 0, Direction.RIGHT, 3, TravelMode.MACH_BIKE) shouldBe
            StepOutcome.Blocked
        FieldRules.step(r, 0, 0, Direction.RIGHT, 3, TravelMode.ACRO_BIKE) shouldBe
            StepOutcome.Walk(1, 0, 3)
      }

      test("directionally impassable tiles block entry and exit through their wall side") {
        val m = map(".e.")
        // an east-blocked tile cannot be entered moving west, nor left moving east
        FieldRules.step(m, 2, 0, Direction.LEFT, 3, TravelMode.WALK) shouldBe StepOutcome.Blocked
        FieldRules.step(m, 0, 0, Direction.RIGHT, 3, TravelMode.WALK) shouldBe
            StepOutcome.Walk(1, 0, 3)
        FieldRules.step(m, 1, 0, Direction.RIGHT, 3, TravelMode.WALK) shouldBe StepOutcome.Blocked
      }

      test("SURF starts only from default-elevation land facing water") {
        val m = map(".~", "0~")
        FieldRules.canStartSurf(m, 0, 0, Direction.RIGHT, 3, TravelMode.WALK) shouldBe true
        FieldRules.canStartSurf(m, 0, 0, Direction.DOWN, 3, TravelMode.WALK) shouldBe false
        FieldRules.canStartSurf(m, 0, 1, Direction.RIGHT, 0, TravelMode.WALK) shouldBe false
        FieldRules.canStartSurf(m, 0, 0, Direction.RIGHT, 3, TravelMode.SURF) shouldBe false
      }

      test("WATERFALL climbs to the first non-waterfall tile above") {
        val m = map("~", "W", "W", "~")
        FieldRules.waterfallClimb(m, 0, 3) shouldBe (0 to 0)
        FieldRules.waterfallClimb(m, 0, 0) shouldBe null
      }

      test("the bike only comes out where the map allows cycling and not on water") {
        FieldRules.canRide(map(".~"), 0, 0, TravelMode.WALK) shouldBe true
        FieldRules.canRide(map(".~"), 1, 0, TravelMode.SURF) shouldBe false
        FieldRules.canRide(map(".", cycling = false), 0, 0, TravelMode.WALK) shouldBe false
      }

      test("HM badges follow each game's field move order") {
        FieldMoves.badgeFor(Region.KANTO, FieldAction.FLASH) shouldBe 1
        FieldMoves.badgeFor(Region.KANTO, FieldAction.CUT) shouldBe 2
        FieldMoves.badgeFor(Region.KANTO, FieldAction.SURF) shouldBe 5
        FieldMoves.badgeFor(Region.KANTO, FieldAction.WATERFALL) shouldBe 7
        FieldMoves.badgeFor(Region.HOENN, FieldAction.CUT) shouldBe 1
        FieldMoves.badgeFor(Region.HOENN, FieldAction.FLY) shouldBe 6
        FieldMoves.badgeFor(Region.HOENN, FieldAction.DIVE) shouldBe 7
        FieldMoves.badgeFor(Region.HOENN, FieldAction.WATERFALL) shouldBe 8
        FieldMoves.badgeFor(Region.KANTO, FieldAction.TELEPORT) shouldBe null
        FieldMoves.existsIn(Region.KANTO, FieldAction.DIVE) shouldBe false
        FieldMoves.badgeFlag(Region.HOENN, 3) shouldBe "hoenn/FLAG_BADGE03_GET"
      }

      test("a trainer sees the player in its line, within its sight, with a clear path") {
        runTest {
          val store = CharacterStore(FakeCharacterRepository(), EntityIdService(), backgroundScope)
          val charId = store.createCharacter(1, "Red", CharacterGender.MALE, Region.KANTO).info.id
          val trainer =
              NpcDef(
                  entityIdx = 0,
                  graphicsId = 20,
                  x = 5,
                  y = 1,
                  elevation = 3,
                  movementType = MovementType.FACE_LEFT,
                  movementRangeX = 0,
                  movementRangeY = 0,
                  trainerType = 1,
                  facing = Direction.LEFT,
                  script = "Test_EventScript_Trainer",
                  trainer = TrainerSpot(5, "TRAINER_TEST", 3, 0, 0, 0),
              )
          val m = map("......", "......", "..#...", npcs = listOf(trainer))
          val field = fieldService(store, MapManager())
          val stored = store.getCharacter(charId)!!
          val state = FakeSession(characterId = charId, regionId = 0, bankId = 3, mapId = 0).state()
          field.spottingTrainer(stored, state, m, 3, 1)!!.let { (npc, dir, dist) ->
            npc.entityIdx shouldBe 0
            dir shouldBe Direction.LEFT
            dist shouldBe 2
          }
          // out of sight (4 tiles), behind it, and on another row
          field.spottingTrainer(stored, state, m, 1, 1) shouldBe null
          field.spottingTrainer(stored, state, m, 5, 0) shouldBe null
          // a defeated trainer stays quiet
          store.setStoryFlag(charId, "kanto/TRAINER_DEFEATED_5")
          field.spottingTrainer(store.getCharacter(charId)!!, state, m, 3, 1) shouldBe null
        }
      }

      test("a wall between trainer and player blocks its sight") {
        runTest {
          val store = CharacterStore(FakeCharacterRepository(), EntityIdService(), backgroundScope)
          val charId = store.createCharacter(1, "Red", CharacterGender.MALE, Region.KANTO).info.id
          val trainer =
              NpcDef(
                  entityIdx = 0,
                  graphicsId = 20,
                  x = 5,
                  y = 0,
                  elevation = 3,
                  movementType = MovementType.FACE_LEFT,
                  movementRangeX = 0,
                  movementRangeY = 0,
                  trainerType = 1,
                  facing = Direction.LEFT,
                  trainer = TrainerSpot(5, "TRAINER_TEST", 4, 0, 0, 0),
              )
          val m = map("..#...", npcs = listOf(trainer))
          val field = fieldService(store, MapManager())
          val state = FakeSession(characterId = charId, regionId = 0, bankId = 3, mapId = 0).state()
          field.spottingTrainer(store.getCharacter(charId)!!, state, m, 1, 0) shouldBe null
          field
              .spottingTrainer(store.getCharacter(charId)!!, state, m, 3, 0)
              .shouldBeInstanceOf<Triple<NpcDef, Direction, Int>>()
        }
      }
    })
