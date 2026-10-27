package de.fiereu.openmmo.server.game.field

import de.fiereu.openmmo.net.game.packets.field.PlacedDecoration
import de.fiereu.openmmo.progression.SecretBaseData
import de.fiereu.openmmo.progression.SecretBaseData.Permission

/**
 * Decorations in a secret base (decoration.c), pure so they can be tested: what a placed set does
 * to the base map ([apply], ShowDecorationOnMap_) and where one more may go ([canPlace],
 * CanPlaceDecoration).
 */
object SecretBaseRules {
  /** One base map cell after the decorations are drawn. */
  data class Cell(val metatile: Int, val behavior: String, val impassable: Boolean)

  /** The base map with [placed] drawn on it, plus the tiles the doll / cushion sprites stand on. */
  class Decorated(
      val base: SecretBaseData.BaseMap,
      val cells: Array<Cell?>,
      val sprites: Set<Int>
  ) {
    fun behaviorAt(x: Int, y: Int): String? {
      if (x !in 0 until base.width || y !in 0 until base.height) return null
      return cells[y * base.width + x]?.behavior ?: base.behaviors[y * base.width + x]
    }

    fun metatileAt(x: Int, y: Int): Int? {
      if (x !in 0 until base.width || y !in 0 until base.height) return null
      return cells[y * base.width + x]?.metatile ?: base.metatiles[y * base.width + x]
    }

    fun spriteAt(x: Int, y: Int): Boolean = y * base.width + x in sprites
  }

  private const val NUM_PRIMARY = 512

  fun apply(base: SecretBaseData.BaseMap, placed: List<PlacedDecoration>): Decorated {
    val cells = arrayOfNulls<Cell>(base.width * base.height)
    val sprites = HashSet<Int>()
    val d = Decorated(base, cells, sprites)
    for (p in placed) {
      val decor = SecretBaseData.decorations[p.decorId] ?: continue
      if (decor.sprite) {
        if (p.x in 0 until base.width && p.y in 0 until base.height)
            sprites += p.y * base.width + p.x
        continue
      }
      for (j in 0 until decor.height) for (i in 0 until decor.width) {
        val x = p.x + i
        val y = p.y - decor.height + 1 + j
        if (x !in 0 until base.width || y !in 0 until base.height) continue
        val tile = decor.tiles.getOrNull(j * decor.width + i) ?: continue
        val attr = SecretBaseData.decorTiles[tile] ?: SecretBaseData.TileAttr("MB_NORMAL", 0)
        val impassable =
            attr.behavior == "MB_SECRET_BASE_IMPASSABLE" ||
                (decor.permission != Permission.PASS_FLOOR && attr.layer != 0)
        // The metatile with the wall background when it overlaps a north wall.
        val wall =
            decor.permission != Permission.NA_WALL &&
                d.behaviorAt(x, y) == "MB_SECRET_BASE_NORTH_WALL"
        val t = if (wall) tile or 1 else tile
        val tAttr = SecretBaseData.decorTiles[t] ?: attr
        cells[y * base.width + x] = Cell(NUM_PRIMARY + t, tAttr.behavior, impassable)
      }
    }
    return d
  }

  /**
   * CanPlaceDecoration for [decor] with its bottom-left at ([x], [y]). [playerX]/[playerY] is where
   * the player stood at the PC (IsntInitialPosition; the player is object 0 and may stand under
   * passable tiles).
   */
  fun canPlace(
      d: Decorated,
      decor: SecretBaseData.Decor,
      x: Int,
      y: Int,
      playerX: Int,
      playerY: Int,
  ): Boolean {
    val w = decor.width
    val h = decor.height
    fun layer(i: Int): Int =
        SecretBaseData.decorTiles[decor.tiles.getOrNull(i) ?: return 0]?.layer ?: 0
    fun initial(cx: Int, cy: Int, layer: Int) = cx == playerX && cy == playerY && layer != 0
    fun inside(cx: Int, cy: Int) = cx in 0 until d.base.width && cy in 0 until d.base.height
    fun player(cx: Int, cy: Int) = cx == playerX && cy == playerY
    when (decor.permission) {
      Permission.SOLID_FLOOR,
      Permission.PASS_FLOOR -> {
        for (i in 0 until h) for (j in 0 until w) {
          val cx = x + j
          val cy = y - i
          if (!inside(cx, cy)) return false
          val b = d.behaviorAt(cx, cy)
          // IsFloorOrBoardAndHole
          val floor =
              b != "MB_SECRET_BASE_TRAINER_SPOT" &&
                  (b == "MB_NORMAL" || (decor.id == SOLID_BOARD && b == "MB_SECRET_BASE_HOLE"))
          if (!floor) return false
          if (initial(cx, cy, layer((h - 1 - i) * w + j))) return false
          if (d.spriteAt(cx, cy)) return false
        }
      }
      Permission.BEHIND_FLOOR -> {
        for (i in 0 until h - 1) for (j in 0 until w) {
          val cx = x + j
          val cy = y - i
          if (!inside(cx, cy)) return false
          val b = d.behaviorAt(cx, cy)
          val l = layer((h - 1 - i) * w + j)
          if (b != "MB_NORMAL" && !(b == "MB_SECRET_BASE_TRAINER_SPOT" && l == 0)) return false
          if (initial(cx, cy, l)) return false
          if (d.spriteAt(cx, cy) || player(cx, cy)) return false
        }
        val top = y - h + 1
        for (j in 0 until w) {
          val cx = x + j
          if (!inside(cx, top)) return false
          val b = d.behaviorAt(cx, top)
          if (b != "MB_NORMAL" && b != "MB_SECRET_BASE_NORTH_WALL") return false
          if (initial(cx, top, layer(j))) return false
          if (d.spriteAt(cx, top)) return false
        }
      }
      Permission.NA_WALL -> {
        for (i in 0 until h) for (j in 0 until w) {
          val cx = x + j
          val cy = y - i
          if (!inside(cx, cy)) return false
          if (d.behaviorAt(cx, cy) != "MB_SECRET_BASE_NORTH_WALL") return false
          if (d.metatileAt(cx, cy + 1) == SAND_ORNAMENT_BROKEN_BASE) return false
        }
      }
      Permission.SPRITE -> {
        for (j in 0 until w) {
          val cx = x + j
          if (!inside(cx, y)) return false
          val b = d.behaviorAt(cx, y)
          val ok =
              if (decor.height == 2) b == "MB_HOLDS_LARGE_DECORATION"
              else b == "MB_HOLDS_SMALL_DECORATION" || b == "MB_HOLDS_LARGE_DECORATION"
          if (!ok) return false
          if (d.spriteAt(cx, y) || player(cx, y)) return false
        }
      }
    }
    return true
  }

  /** The placed decoration that covers ([x], [y]), for PUT AWAY. */
  fun at(placed: List<PlacedDecoration>, x: Int, y: Int): Int =
      placed.indexOfLast { p ->
        val decor = SecretBaseData.decorations[p.decorId] ?: return@indexOfLast false
        if (decor.sprite) p.x == x && p.y == y
        else x in p.x until p.x + decor.width && y in (p.y - decor.height + 1)..p.y
      }

  /** DECOR_SOLID_BOARD, the one decoration that may cover a hole. */
  private const val SOLID_BOARD = 33

  /** METATILE_SecretBase_SandOrnament_BrokenBase. */
  private const val SAND_ORNAMENT_BROKEN_BASE = 0x28C
}
