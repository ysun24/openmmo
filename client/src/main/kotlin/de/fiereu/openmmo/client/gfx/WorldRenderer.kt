package de.fiereu.openmmo.client.gfx

import de.fiereu.openmmo.client.world.WorldState
import de.fiereu.openmmo.common.enums.Direction
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File

/** GBA screen size; the view is drawn at this resolution and scaled up by the window. */
const val SCREEN_W = 240
const val SCREEN_H = 160

/**
 * Draws the world state over the decomp's own map data. Map images are built once per layout, since
 * a GBA map is static apart from tile animations, which this does not do yet.
 */
class WorldRenderer(private val decompRoot: File) {
  private val data = mutableMapOf<GbaGame, PretData>()
  private val mapImages = mutableMapOf<Triple<Int, Int, Int>, BufferedImage?>()
  private val sprites = mutableMapOf<GbaGame, BufferedImage?>()

  private fun pret(game: GbaGame) = data.getOrPut(game) { PretData(decompRoot, game) }

  fun mapName(regionId: Int, bank: Int, map: Int): String? =
      GbaGame.forRegion(regionId)?.let { pret(it).mapName(bank, map) }

  fun render(snap: WorldState.Snapshot, out: BufferedImage = newFrame()): BufferedImage {
    val g = out.createGraphics()
    g.color = Color.BLACK
    g.fillRect(0, 0, out.width, out.height)
    val key = snap.map
    val self = snap.self
    if (key == null || self == null) {
      text(g, "Connecting...", 8, 80)
      g.dispose()
      return out
    }
    val game = GbaGame.forRegion(key.regionId)
    // Camera: keep the player's tile at the GBA's usual spot, just above screen centre.
    val camX = self.x * 16 - (SCREEN_W / 2 - 8)
    val camY = self.y * 16 - (SCREEN_H / 2 - 16)
    val mapImg =
        game?.let {
          mapImages.getOrPut(Triple(key.regionId, key.bankId, key.mapId)) {
            buildMap(it, key.bankId, key.mapId)
          }
        }
    if (mapImg != null) g.drawImage(mapImg, -camX, -camY, null)

    val sprite = game?.let { sprites.getOrPut(it) { loadPlayerSprite(it) } }
    snap.entities.values
        .filter { it.map == key }
        .sortedBy { it.y }
        .forEach { e ->
          val sx = e.x * 16 - camX
          val sy = e.y * 16 - camY - 16
          if (sprite != null) drawFrame(g, sprite, e.facing, sx, sy)
          else {
            g.color = if (e.id == snap.selfId) Color.RED else Color.CYAN
            g.fillRect(sx + 2, sy + 18, 12, 12)
          }
          if (e.id != snap.selfId) label(g, e.name, sx + 8, sy - 2)
        }

    // Chat, bottom-left, the last few lines.
    val lines = snap.chat.takeLast(4)
    if (lines.isNotEmpty()) {
      g.color = Color(0, 0, 0, 150)
      g.fillRect(0, SCREEN_H - 10 * lines.size - 2, SCREEN_W, 10 * lines.size + 2)
      lines.forEachIndexed { i, l ->
        text(g, "${l.sender}: ${l.message}", 2, SCREEN_H - 10 * (lines.size - i) + 7)
      }
    }
    text(g, mapName(key.regionId, key.bankId, key.mapId) ?: "$key", 2, 9)
    g.dispose()
    return out
  }

  private fun buildMap(game: GbaGame, bank: Int, map: Int): BufferedImage? {
    val layout = pret(game).layout(bank, map) ?: return null
    val img = BufferedImage(layout.width * 16, layout.height * 16, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until layout.height) for (x in 0 until layout.width) {
      val id = layout.metatileAt(x, y) ?: continue
      drawMetatile(img, game, layout, id, x * 16, y * 16)
    }
    return img
  }

  private fun drawMetatile(
      img: BufferedImage,
      game: GbaGame,
      layout: LayoutData,
      id: Int,
      px: Int,
      py: Int,
  ) {
    val meta =
        if (id < game.metatilesInPrimary) layout.primary.metatiles.getOrNull(id)
        else layout.secondary.metatiles.getOrNull(id - game.metatilesInPrimary)
    meta ?: return
    // Bottom layer then top layer; colour 0 is transparent on both, the backdrop shows through.
    val backdrop = layout.primary.palettes[0]?.get(0) ?: 0xFF000000.toInt()
    for (yy in 0 until 16) for (xx in 0 until 16) img.setRGB(px + xx, py + yy, backdrop)
    meta.forEachIndexed { i, ref ->
      val q = i % 4
      drawTile(img, game, layout, ref, px + (q % 2) * 8, py + (q / 2) * 8)
    }
  }

  private fun drawTile(
      img: BufferedImage,
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
      img.setRGB(px + tx, py + ty, pal[c])
    }
  }

  private fun loadPlayerSprite(game: GbaGame): BufferedImage? {
    val base = File(decompRoot, game.dir)
    val candidates =
        when (game) {
          GbaGame.FIRERED -> listOf("graphics/object_events/pics/people/red_normal.png")
          GbaGame.EMERALD ->
              listOf(
                  "graphics/object_events/pics/people/brendan/walking.png",
                  "graphics/object_events/pics/people/brendan_normal.png")
        }
    return candidates.map { File(base, it) }.firstOrNull { it.exists() }?.let(PretData::readSprite)
  }

  /** Sheets are 16x32 frames: 0 down, 1 up, 2 left; right is left mirrored. */
  private fun drawFrame(g: Graphics2D, sheet: BufferedImage, facing: Direction, x: Int, y: Int) {
    val (frame, mirror) =
        when (facing) {
          Direction.UP -> 1 to false
          Direction.LEFT -> 2 to false
          Direction.RIGHT -> 2 to true
          else -> 0 to false
        }
    val sx = frame * 16
    if (mirror) g.drawImage(sheet, x + 16, y, x, y + 32, sx, 0, sx + 16, 32, null)
    else g.drawImage(sheet, x, y, x + 16, y + 32, sx, 0, sx + 16, 32, null)
  }

  private fun text(g: Graphics2D, s: String, x: Int, y: Int) {
    g.setRenderingHint(
        RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
    g.font = FONT
    g.color = Color.BLACK
    g.drawString(s, x + 1, y + 1)
    g.color = Color.WHITE
    g.drawString(s, x, y)
  }

  private fun label(g: Graphics2D, s: String, cx: Int, y: Int) {
    g.font = FONT
    val w = g.fontMetrics.stringWidth(s)
    text(g, s, cx - w / 2, y)
  }

  companion object {
    private val FONT = Font(Font.MONOSPACED, Font.PLAIN, 8)

    fun newFrame() = BufferedImage(SCREEN_W, SCREEN_H, BufferedImage.TYPE_INT_ARGB)
  }
}
