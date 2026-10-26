package de.fiereu.openmmo.client.gfx

import de.fiereu.openmmo.client.world.Entity
import de.fiereu.openmmo.client.world.MapKey
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

/** How long one walking step takes on screen, the GBA's 16 frames at 60 fps. */
private const val STEP_MS = 250L

/**
 * Draws the world state over the decomp's own map data: the current map with its seamless
 * connections and border, sprites from the decomp's object event table, and the map's top layer
 * above them. Map images are built once per layout, since a GBA map is static apart from tile
 * animations, which this does not do yet.
 */
class WorldRenderer(private val decompRoot: File) {
  private val data = mutableMapOf<GbaGame, PretData>()
  private val objects = mutableMapOf<GbaGame, ObjectEventGraphics>()
  private val mapImages = mutableMapOf<Triple<Int, Int, Int>, MapImage?>()
  private val metatileImages = mutableMapOf<Pair<String, Int>, MapImage>()
  private val motions = mutableMapOf<Long, Motion>()
  private val overlay = OverworldOverlay()

  /** Bottom layer (with everything sprites stand on) and top layer (drawn above sprites). */
  private class MapImage(val bottom: BufferedImage, val top: BufferedImage)

  /** One entity's last step: drawn sliding from [from] to [to] over [STEP_MS]. */
  private data class Motion(
      val fromMap: MapKey,
      val fromX: Int,
      val fromY: Int,
      val toMap: MapKey,
      val toX: Int,
      val toY: Int,
      val start: Long,
      val stepCount: Int,
  )

  private fun pret(game: GbaGame) = data.getOrPut(game) { PretData(decompRoot, game) }

  private fun objectGfx(game: GbaGame) =
      objects.getOrPut(game) { ObjectEventGraphics(decompRoot, game) }

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
    val now = System.currentTimeMillis()
    val origins = game?.let { originsAround(it, key) } ?: mapOf(key to (0 to 0))

    // Camera: keep the player's tile at the GBA's usual spot, just above screen centre.
    val (selfPx, selfPy) = pixelPos(self, origins, now)
    val camX = selfPx - (SCREEN_W / 2 - 8)
    val camY = selfPy - (SCREEN_H / 2 - 16)

    val images = mutableListOf<Triple<MapImage, Int, Int>>()
    if (game != null) {
      drawBorder(g, game, key, origins, camX, camY)
      for ((k, o) in origins) {
        val img = mapImage(game, k) ?: continue
        images += Triple(img, o.first * 16 - camX, o.second * 16 - camY)
      }
      images.forEach { (img, x, y) -> g.drawImage(img.bottom, x, y, null) }
    }

    snap.entities.values
        .filter { it.visible && it.map in origins }
        .map { it to pixelPos(it, origins, now) }
        .sortedBy { it.second.second }
        .forEach { (e, pos) ->
          val sx = pos.first - camX
          val sy = pos.second - camY
          drawEntity(g, game, e, sx, sy, now, snap.selfId)
        }
    // Forget motions of entities that are gone.
    motions.keys.retainAll(snap.entities.keys)

    images.forEach { (img, x, y) -> g.drawImage(img.top, x, y, null) }

    snap.entities.values
        .filter { it.isPlayer && it.id != snap.selfId && it.map in origins }
        .forEach { e ->
          val (px, py) = pixelPos(e, origins, now)
          label(g, e.name, px - camX + 8, py - camY - 18)
        }

    // Chat, bottom-left, the last few lines; hidden while a box covers that part of the screen.
    val ui = snap.overworld
    val lines = snap.chat.takeLast(4)
    if (lines.isNotEmpty() && ui?.dialog == null) {
      g.color = Color(0, 0, 0, 150)
      g.fillRect(0, SCREEN_H - 10 * lines.size - 2, SCREEN_W, 10 * lines.size + 2)
      lines.forEachIndexed { i, l ->
        text(g, "${l.sender}: ${l.message}", 2, SCREEN_H - 10 * (lines.size - i) + 7)
      }
    }
    text(g, mapName(key.regionId, key.bankId, key.mapId) ?: "$key", 2, 9)
    if (ui != null) overlay.draw(g, ui)
    g.dispose()
    return out
  }

  // --- maps -------------------------------------------------------------------------------------

  /**
   * Tile origin of the current map (0, 0) and of each map connected to it, from the decomp's
   * map.json connections, in the current map's tile coordinates.
   */
  private fun originsAround(game: GbaGame, key: MapKey): Map<MapKey, Pair<Int, Int>> {
    val out = linkedMapOf(key to (0 to 0))
    val p = pret(game)
    val layout = p.layout(key.bankId, key.mapId) ?: return out
    for (c in layout.connections) {
      val (bank, map) = p.addressOf(c.mapName) ?: continue
      val other = p.layout(bank, map) ?: continue
      val o =
          when (c.direction) {
            "up" -> c.offset to -other.height
            "down" -> c.offset to layout.height
            "left" -> -other.width to c.offset
            "right" -> layout.width to c.offset
            else -> continue
          }
      out.putIfAbsent(MapKey(key.regionId, bank, map), o)
    }
    return out
  }

  private fun mapImage(game: GbaGame, key: MapKey): MapImage? =
      mapImages.getOrPut(Triple(key.regionId, key.bankId, key.mapId)) {
        val layout = pret(game).layout(key.bankId, key.mapId) ?: return@getOrPut null
        val w = layout.width * 16
        val h = layout.height * 16
        val bottom = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val top = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until layout.height) for (x in 0 until layout.width) {
          val id = layout.metatileAt(x, y) ?: continue
          drawMetatile(bottom, top, game, layout, id, x * 16, y * 16)
        }
        MapImage(bottom, top)
      }

  /** Fills the screen outside every drawn map with the current map's border blocks. */
  private fun drawBorder(
      g: Graphics2D,
      game: GbaGame,
      key: MapKey,
      origins: Map<MapKey, Pair<Int, Int>>,
      camX: Int,
      camY: Int,
  ) {
    val p = pret(game)
    val layout = p.layout(key.bankId, key.mapId) ?: return
    if (layout.border.isEmpty()) return
    val rects =
        origins.mapNotNull { (k, o) ->
          p.layout(k.bankId, k.mapId)?.let { intArrayOf(o.first, o.second, it.width, it.height) }
        }
    val tx0 = Math.floorDiv(camX, 16)
    val ty0 = Math.floorDiv(camY, 16)
    for (ty in ty0..ty0 + SCREEN_H / 16 + 1) for (tx in tx0..tx0 + SCREEN_W / 16 + 1) {
      if (rects.any { r -> tx >= r[0] && tx < r[0] + r[2] && ty >= r[1] && ty < r[1] + r[3] })
          continue
      val bw = layout.borderWidth
      val bh = layout.borderHeight
      // The GBA picks border blocks by the parity of x+1 and y+1 for its 2x2 borders.
      val bx = Math.floorMod(tx + 1, bw)
      val by = Math.floorMod(ty + 1, bh)
      val id = layout.border.getOrNull(by * bw + bx)?.and(0x3FF) ?: continue
      val img =
          metatileImages.getOrPut(layout.name to id) {
            val b = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            val t = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            drawMetatile(b, t, game, layout, id, 0, 0)
            MapImage(b, t)
          }
      g.drawImage(img.bottom, tx * 16 - camX, ty * 16 - camY, null)
      g.drawImage(img.top, tx * 16 - camX, ty * 16 - camY, null)
    }
  }

  /**
   * One metatile. Bottom layer onto [bottom]; the top layer goes onto [top], above sprites, unless
   * the metatile's layer type is COVERED, in which case both layers sit below sprites.
   */
  private fun drawMetatile(
      bottom: BufferedImage,
      top: BufferedImage,
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
    val layerType = set.attributes.getOrNull(local)?.let(game::layerType) ?: LAYER_NORMAL
    // Colour 0 is transparent on both layers, the backdrop shows through.
    val backdrop = layout.primary.palettes[0]?.get(0) ?: 0xFF000000.toInt()
    for (yy in 0 until 16) for (xx in 0 until 16) bottom.setRGB(px + xx, py + yy, backdrop)
    meta.forEachIndexed { i, ref ->
      val q = i % 4
      val target = if (i < 4 || layerType == LAYER_COVERED) bottom else top
      drawTile(target, game, layout, ref, px + (q % 2) * 8, py + (q / 2) * 8)
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

  // --- sprites ----------------------------------------------------------------------------------

  /** Top-left pixel of the entity's tile in current-map pixels, sliding while it walks. */
  private fun pixelPos(e: Entity, origins: Map<MapKey, Pair<Int, Int>>, now: Long): Pair<Int, Int> {
    val m = motionOf(e, now)
    val to = tilePx(m.toMap, m.toX, m.toY, origins) ?: (e.x * 16 to e.y * 16)
    val t = ((now - m.start).toDouble() / STEP_MS).coerceIn(0.0, 1.0)
    if (t >= 1.0) return to
    val from = tilePx(m.fromMap, m.fromX, m.fromY, origins) ?: return to
    // A jump of more than one tile (a warp or a reset) is not walked.
    if (Math.abs(from.first - to.first) + Math.abs(from.second - to.second) > 32) return to
    return (from.first + ((to.first - from.first) * t).toInt()) to
        (from.second + ((to.second - from.second) * t).toInt())
  }

  private fun tilePx(
      key: MapKey,
      x: Int,
      y: Int,
      origins: Map<MapKey, Pair<Int, Int>>
  ): Pair<Int, Int>? {
    val o = origins[key] ?: return null
    return (o.first + x) * 16 to (o.second + y) * 16
  }

  private fun motionOf(e: Entity, now: Long): Motion {
    val m = motions[e.id]
    if (m == null) {
      return Motion(e.map, e.x, e.y, e.map, e.x, e.y, 0, 0).also { motions[e.id] = it }
    }
    if (m.toMap == e.map && m.toX == e.x && m.toY == e.y) return m
    return Motion(m.toMap, m.toX, m.toY, e.map, e.x, e.y, now, m.stepCount + 1).also {
      motions[e.id] = it
    }
  }

  private fun drawEntity(
      g: Graphics2D,
      game: GbaGame?,
      e: Entity,
      sx: Int,
      sy: Int,
      now: Long,
      selfId: Long?,
  ) {
    val sprite =
        game?.let {
          val gfx = objectGfx(it)
          if (e.graphicsId < 0) gfx.playerSprite(female = false) else gfx.sprite(e.graphicsId)
        }
    if (sprite == null) {
      g.color = if (e.id == selfId) Color.RED else Color.CYAN
      g.fillRect(sx + 2, sy + 2, 12, 12)
      return
    }
    val m = motions[e.id]
    val walking = m != null && now - m.start < STEP_MS
    // Two stepping frames, alternating step by step, shown for the middle of each step.
    val phase =
        if (!walking) 0
        else {
          val t = (now - m!!.start).toDouble() / STEP_MS
          if (t < 0.25 || t > 0.85) 0 else 1 + (m.stepCount and 1)
        }
    val frame = sprite.frameFor(e.facing.ordinal.coerceAtMost(3), phase)
    val w = sprite.width
    val h = sprite.height
    val x = sx + 8 - w / 2
    val y = sy + 16 - h
    val srcX = frame * w
    if (srcX + w > sprite.sheet.width) return
    if (e.facing == Direction.RIGHT)
        g.drawImage(sprite.sheet, x + w, y, x, y + h, srcX, 0, srcX + w, h, null)
    else g.drawImage(sprite.sheet, x, y, x + w, y + h, srcX, 0, srcX + w, h, null)
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
    private const val LAYER_NORMAL = 0
    private const val LAYER_COVERED = 1

    fun newFrame() = BufferedImage(SCREEN_W, SCREEN_H, BufferedImage.TYPE_INT_ARGB)
  }
}
