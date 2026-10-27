package de.fiereu.openmmo.web.assets

import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import java.io.File
import javax.imageio.ImageIO
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Window frames for CSS `border-image` (9-slice), composed exactly the way the games' tilemap code
 * lays the 8x8 frame tiles out:
 * - the dialogue box (`WindowFunc_DrawDialogueFrame`: Emerald `menu.c` over `message_box.png`,
 *   FireRed `new_menu_helpers.c` over `menu_message.png`);
 * - the standard menu frame (`DrawStdFrame...`: tiles 0..8 in a 3x3 grid) for every user frame
 *   style the options menu offers (Emerald `text_window/1..20.png`, FireRed `type1..10.png`).
 * Each PNG keeps the frame's own palette; window-fill pixels use palette colour 1 like
 * `FillWindowPixelBuffer(PIXEL_FILL(1))`.
 */
class UiExport(private val decompRoot: File, private val out: Output) {
  val failures: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

  /** tile (index, vflip, hflip) at cell; -1 = window fill (colour 1); null = transparent */
  private class Placed(val tile: Int, val vflip: Boolean = false)

  private class Sheet(val img: BufferedImage) {
    val cm = img.colorModel as IndexColorModel
    val cols = img.width / 8

    fun index(tile: Int, x: Int, y: Int): Int =
        img.raster.getSample((tile % cols) * 8 + x, (tile / cols) * 8 + y, 0) and 0xF

    fun rgb(i: Int): Int = cm.getRGB(i) or (0xFF shl 24)
  }

  private fun compose(sheet: Sheet, grid: List<List<Placed?>>): BufferedImage {
    val h = grid.size
    val w = grid[0].size
    val img = BufferedImage(w * 8, h * 8, BufferedImage.TYPE_INT_ARGB)
    for (r in 0 until h) for (c in 0 until w) {
      val p = grid[r][c] ?: continue
      for (y in 0 until 8) for (x in 0 until 8) {
        val i =
            if (p.tile < 0) 1 else sheet.index(p.tile, x, if (p.vflip) 7 - y else y)
        if (i != 0) img.setRGB(c * 8 + x, r * 8 + y, sheet.rgb(i))
      }
    }
    return img
  }

  private fun sheet(game: String, path: String): Sheet? {
    val f = File(decompRoot, "$game/$path")
    if (!f.isFile) return null
    val img = ImageIO.read(f)
    if (img.colorModel !is IndexColorModel) return null
    return Sheet(img)
  }

  /** Emerald: 5 columns (L-2, L-1, interior, L+w-1, L+w) x 3 rows (top, body, bottom). */
  private fun emeraldDialog(s: Sheet): BufferedImage {
    fun row(a: Int, b: Int, m: Int, d: Int, e: Int, v: Boolean = false) =
        listOf(Placed(a, v), Placed(b, v), Placed(m, v), Placed(d, v), Placed(e, v))
    return compose(s, listOf(row(1, 3, 4, 5, 6), row(7, 9, 9, 9, 10), row(1, 3, 4, 5, 6, true)))
  }

  /** FireRed: 5 columns (L-2, L-1, interior, L+w, L+w+1) x 6 rows (top-1 .. top+4). */
  private fun fireredDialog(s: Sheet): BufferedImage {
    val fill = Placed(-1)
    fun side(a: Int, b: Int, d: Int, e: Int, v: Boolean = false, mid: Placed? = fill) =
        listOf(Placed(a, v), Placed(b, v), mid, Placed(d, v), Placed(e, v))
    return compose(
        s,
        listOf(
            side(0, 1, 3, 4, mid = Placed(2)),
            side(5, 6, 8, 9),
            side(10, 11, 12, 13),
            side(10, 11, 12, 13, true),
            side(5, 6, 8, 9, true),
            side(0, 1, 3, 4, true, mid = Placed(2, true)),
        ))
  }

  /** The 3x3 standard frame; the centre is window fill. */
  private fun stdFrame(s: Sheet): BufferedImage {
    val g = listOf(listOf(0, 1, 2), listOf(3, -1, 5), listOf(6, 7, 8))
    return compose(s, g.map { r -> r.map { Placed(it) } })
  }

  fun export(): JsonObject = buildJsonObject {
    for ((region, game) in REGIONS) {
      val dir = game.dir
      val short = dir.removePrefix("poke")
      runCatching {
            val dlg =
                if (dir == "pokeemerald")
                    sheet(dir, "graphics/text_window/message_box.png")?.let(::emeraldDialog)
                else sheet(dir, "graphics/text_window/menu_message.png")?.let(::fireredDialog)
            val frames =
                (1..20).mapNotNull { n ->
                  val p =
                      if (dir == "pokeemerald") "graphics/text_window/$n.png"
                      else "graphics/text_window/type$n.png"
                  sheet(dir, p)?.let { out.writePng("ui/$short-frame$n.png", stdFrame(it)) }
                }
            put(
                "$region",
                buildJsonObject {
                  dlg?.let {
                    put("dialog", out.writePng("ui/$short-dialog.png", it))
                    // border-image-slice (top right bottom left) in source pixels
                    putJsonArray("dialogSlice") {
                      val v = if (dir == "pokeemerald") listOf(8, 16, 8, 16) else listOf(16, 16, 16, 16)
                      v.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                    }
                  }
                  // "more text" arrow: TextPrinterDrawDownArrow blits an 8x16 window of this
                  // sheet at sDownArrowYCoords (Emerald: source y 0,1,2,1; FireRed: source x
                  // 0,16,32,16), one step per 8 frames.
                  val arrowSrc =
                      if (dir == "pokeemerald") "graphics/fonts/down_arrow.png"
                      else "graphics/fonts/down_arrows.png"
                  File(decompRoot, "$dir/$arrowSrc")
                      .takeIf { it.isFile }
                      ?.let { Decomp.readIndexed(it, null) }
                      ?.let { img ->
                        put("arrow", out.writePng("ui/$short-arrow.png", img))
                        put("arrowSize", "${img.width}x${img.height}")
                        putJsonArray("arrowSteps") {
                          val steps =
                              if (dir == "pokeemerald") listOf(0 to 0, 0 to 1, 0 to 2, 0 to 1)
                              else listOf(0 to 0, 16 to 0, 32 to 0, 16 to 0)
                          steps.forEach { (x, y) ->
                            add(
                                kotlinx.serialization.json.buildJsonArray {
                                  add(kotlinx.serialization.json.JsonPrimitive(x))
                                  add(kotlinx.serialization.json.JsonPrimitive(y))
                                })
                          }
                        }
                      }
                  putJsonArray("frames") {
                    frames.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                  }
                  put("frameSlice", 8)
                })
          }
          .onFailure { failures += "ui frames $region: $it" }
    }
  }
}
