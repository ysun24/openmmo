package de.fiereu.openmmo.server.game.field

import de.fiereu.openmmo.net.game.packets.field.PlacedDecoration
import de.fiereu.openmmo.progression.SecretBaseData
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** r4: decoration.c CanPlaceDecoration / ShowDecorationOnMap_ on the decomp's base maps. */
class SecretBaseRulesTest :
    FunSpec({
      // SecretBase_RedCave1 (11x9): north wall on row 0, PC at (1, 2), floor x1..8 y1..6.
      val base = SecretBaseData.baseMaps.first { it.map == "SecretBase_RedCave1" }
      fun decor(id: Int) = SecretBaseData.decorations.getValue(id)
      val smallDesk = decor(1)
      val heavyDesk = decor(3) // 3x2
      val chair = decor(10) // PASS_FLOOR
      val poster = decor(66) // NA_WALL
      val doll = decor(76) // SPRITE

      test("the generated tables hold the decomp's bases, spots and decorations") {
        SecretBaseData.baseMaps.size shouldBe 24
        base.pcX shouldBe 1
        base.pcY shouldBe 3
        base.behaviorAt(1, 2) shouldBe "MB_SECRET_BASE_PC"
        base.behaviorAt(4, 0) shouldBe "MB_SECRET_BASE_NORTH_WALL"
        SecretBaseData.spots.size shouldBe 75
        SecretBaseData.spots.first { it.map == "Route111" && it.x == 24 && it.y == 36 }.id shouldBe
            131
        SecretBaseData.baseMapOf(131)!!.map shouldBe "SecretBase_YellowCave2"
        heavyDesk.width shouldBe 3
        heavyDesk.height shouldBe 2
        doll.sprite shouldBe true
        poster.permission shouldBe SecretBaseData.Permission.NA_WALL
      }

      test("desks go on plain floor only, never where the player stands under a covered tile") {
        val empty = SecretBaseRules.apply(base, emptyList())
        SecretBaseRules.canPlace(empty, smallDesk, 3, 3, 1, 3) shouldBe true
        SecretBaseRules.canPlace(empty, smallDesk, 0, 3, 1, 3) shouldBe false // wall
        SecretBaseRules.canPlace(empty, smallDesk, 1, 2, 1, 3) shouldBe false // the PC
        SecretBaseRules.canPlace(empty, smallDesk, 7, 4, 1, 3) shouldBe false // trainer spot
        SecretBaseRules.canPlace(empty, smallDesk, 1, 3, 1, 3) shouldBe false // the player
        SecretBaseRules.canPlace(empty, heavyDesk, 2, 4, 1, 3) shouldBe true
        SecretBaseRules.canPlace(empty, heavyDesk, 7, 4, 1, 3) shouldBe false // off the floor
        // a chair (PASS_FLOOR) goes on the floor too
        SecretBaseRules.canPlace(empty, chair, 5, 5, 1, 3) shouldBe true
      }

      test("a placed desk blocks, holds dolls on its top and cannot be overlapped") {
        val d = SecretBaseRules.apply(base, listOf(PlacedDecoration(3, 2, 4)))
        // rows 3..4, columns 2..4
        val top = d.cells[3 * base.width + 2]!!
        top.impassable shouldBe true
        top.behavior shouldBe "MB_HOLDS_SMALL_DECORATION"
        d.cells[4 * base.width + 3]!!.behavior shouldBe "MB_SECRET_BASE_DECORATION_BASE"
        SecretBaseRules.canPlace(d, smallDesk, 3, 4, 1, 3) shouldBe false
        SecretBaseRules.canPlace(d, doll, 2, 3, 1, 3) shouldBe true
        SecretBaseRules.canPlace(d, doll, 3, 3, 1, 3) shouldBe true // the large middle
        SecretBaseRules.canPlace(d, doll, 2, 4, 1, 3) shouldBe false // the desk's legs
        SecretBaseRules.canPlace(d, doll, 5, 5, 1, 3) shouldBe false // the floor
        val withDoll =
            SecretBaseRules.apply(
                base, listOf(PlacedDecoration(3, 2, 4), PlacedDecoration(76, 2, 3)))
        SecretBaseRules.canPlace(withDoll, doll, 2, 3, 1, 3) shouldBe false
        withDoll.spriteAt(2, 3) shouldBe true
      }

      test("posters hang on the north wall only") {
        val empty = SecretBaseRules.apply(base, emptyList())
        SecretBaseRules.canPlace(empty, poster, 4, 0, 1, 3) shouldBe true
        SecretBaseRules.canPlace(empty, poster, 4, 3, 1, 3) shouldBe false
      }

      test("put away finds the decoration covering a tile") {
        val placed = listOf(PlacedDecoration(3, 2, 4), PlacedDecoration(1, 6, 5))
        SecretBaseRules.at(placed, 4, 3) shouldBe 0
        SecretBaseRules.at(placed, 6, 5) shouldBe 1
        SecretBaseRules.at(placed, 6, 4) shouldBe -1
      }
    })
