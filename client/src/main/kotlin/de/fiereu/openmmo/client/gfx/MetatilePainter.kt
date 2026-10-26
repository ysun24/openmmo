package de.fiereu.openmmo.client.gfx

import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt

/**
 * Paints metatiles into ARGB pixel buffers, split into what sits below sprites and what covers
 * them, the way the GBA assigns a metatile's two layers to its background layers:
 * - NORMAL (0): bottom layer below sprites, top layer above them.
 * - COVERED (1): both layers below sprites.
 * - SPLIT (2): bottom layer below sprites, top layer above them.
 *
 * Shared by the desktop [WorldRenderer] and the web asset exporter, so both split layers alike.
 */
object MetatilePainter {
  const val LAYER_NORMAL = 0
  const val LAYER_COVERED = 1
  const val LAYER_SPLIT = 2

  /** An ARGB pixel buffer of [width] x [height], row-major. */
  class Pixels(val width: Int, val height: Int, val data: IntArray = IntArray(width * height)) {
    companion object {
      /** Wraps a TYPE_INT_ARGB image's own buffer, so writes land in the image. */
      fun of(img: BufferedImage): Pixels {
        require(img.type == BufferedImage.TYPE_INT_ARGB) { "TYPE_INT_ARGB only" }
        return Pixels(img.width, img.height, (img.raster.dataBuffer as DataBufferInt).data)
      }
    }

    fun toImage(): BufferedImage {
      val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
      System.arraycopy(data, 0, (img.raster.dataBuffer as DataBufferInt).data, 0, data.size)
      return img
    }
  }

  /** The layer type of metatile [id] in [layout], NORMAL when the tileset has no attributes. */
  fun layerType(game: GbaGame, layout: LayoutData, id: Int): Int {
    val primary = id < game.metatilesInPrimary
    val set = if (primary) layout.primary else layout.secondary
    val local = if (primary) id else id - game.metatilesInPrimary
    return set.attributes.getOrNull(local)?.let(game::layerType) ?: LAYER_NORMAL
  }

  /** The colour behind everything: colour 0 of the primary tileset's palette 0. */
  fun backdrop(layout: LayoutData): Int = layout.primary.palettes[0]?.get(0) ?: 0xFF000000.toInt()

  /**
   * One metatile at pixel ([px], [py]). The backdrop and the bottom layer go onto [bottom]; the top
   * layer goes onto [top] unless the metatile is COVERED, in which case it joins [bottom].
   */
  fun drawMetatile(
      bottom: Pixels,
      top: Pixels,
      game: GbaGame,
      layout: LayoutData,
      id: Int,
      px: Int,
      py: Int,
  ) {
    val primary = id < game.metatilesInPrimary
    val set = if (primary) layout.primary else layout.secondary
    val local = if (primary) id else id - game.metatilesInPrimary
    val meta = set.metatiles.getOrNull(local) ?: return
    val layerType = layerType(game, layout, id)
    // Colour 0 is transparent on both layers, the backdrop shows through.
    val backdrop = backdrop(layout)
    for (yy in 0 until 16) {
      val row = (py + yy) * bottom.width + px
      for (xx in 0 until 16) bottom.data[row + xx] = backdrop
    }
    meta.forEachIndexed { i, ref ->
      val q = i % 4
      val target = if (i < 4 || layerType == LAYER_COVERED) bottom else top
      drawTile(target, game, layout, ref, px + (q % 2) * 8, py + (q / 2) * 8)
    }
  }

  private fun drawTile(
      img: Pixels,
      game: GbaGame,
      layout: LayoutData,
      ref: TileRef,
      px: Int,
      py: Int,
  ) {
    val tile =
        if (ref.tile < game.tilesInPrimary) layout.primary.tiles.getOrNull(ref.tile)
        else layout.secondary.tiles.getOrNull(ref.tile - game.tilesInPrimary)
    tile ?: return
    val pal =
        (if (ref.palette < game.palsInPrimary) layout.primary.palettes[ref.palette]
        else layout.secondary.palettes[ref.palette]) ?: return
    for (i in 0 until 64) {
      val c = tile[i]
      if (c == 0) continue
      var tx = i % 8
      var ty = i / 8
      if (ref.hFlip) tx = 7 - tx
      if (ref.vFlip) ty = 7 - ty
      img.data[(py + ty) * img.width + px + tx] = pal[c]
    }
  }
}
