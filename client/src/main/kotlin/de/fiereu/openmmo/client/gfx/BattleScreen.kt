package de.fiereu.openmmo.client.gfx

import de.fiereu.openmmo.client.net.BattleMon
import de.fiereu.openmmo.client.net.BattlePhase
import de.fiereu.openmmo.client.net.BattleState
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/** Which menu the battle UI shows. FIGHT lists moves, PARTY lists the party for a switch. */
enum class BattleMenu {
  MAIN,
  FIGHT,
  PARTY,
}

data class BattleUi(val menu: BattleMenu = BattleMenu.MAIN, val cursor: Int = 0)

/**
 * Draws a [BattleState] in the GBA layout: the foe top right with its box top left, our monster's
 * back bottom left with its box on the right, and the text box with the command menu at the bottom.
 * Sprites come from the pret decomps, a placeholder stands in for a missing one.
 *
 * Everything is laid out on the 240x160 GBA grid and drawn [scale] times larger, so text stays
 * crisp while sprites keep their pixels.
 */
class BattleScreen(private val decompRoot: File, private val scale: Int = 3) {
  private val sprites = ConcurrentHashMap<String, BufferedImage>()

  fun newFrame(): BufferedImage =
      BufferedImage(SCREEN_W * scale, SCREEN_H * scale, BufferedImage.TYPE_INT_ARGB)

  fun render(
      s: BattleState,
      ui: BattleUi = BattleUi(),
      out: BufferedImage = newFrame()
  ): BufferedImage {
    val g = out.createGraphics()
    try {
      g.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
      g.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g.scale(scale.toDouble(), scale.toDouble())
      background(g)
      s.foe?.takeIf { it.revealed && !it.fainted }?.let { sprite(g, it.species, true, 144, 4) }
      s.me?.takeIf { !it.fainted }?.let { sprite(g, it.species, false, 32, 48) }
      s.foe?.takeIf { it.revealed }?.let { hpBox(g, it, 8, 10, enemy = true) }
      s.me?.let { hpBox(g, it, 126, 70, enemy = false) }
      textBox(g, s, ui)
    } finally {
      g.dispose()
    }
    return out
  }

  private fun background(g: Graphics2D) {
    g.paint = java.awt.GradientPaint(0f, 0f, Color(248, 248, 216), 0f, 112f, Color(200, 232, 184))
    g.fillRect(0, 0, SCREEN_W, 112)
    // The two grass platforms the monsters stand on.
    g.color = Color(152, 208, 120)
    g.fill(Ellipse2D.Double(128.0, 48.0, 104.0, 24.0))
    g.fill(Ellipse2D.Double(8.0, 96.0, 120.0, 26.0))
    g.color = Color(112, 176, 88)
    g.stroke = BasicStroke(1f)
    g.draw(Ellipse2D.Double(128.0, 48.0, 104.0, 24.0))
    g.draw(Ellipse2D.Double(8.0, 96.0, 120.0, 26.0))
  }

  private fun sprite(g: Graphics2D, species: Int, front: Boolean, x: Int, y: Int) {
    val img = spriteFor(species, front)
    if (img != null) {
      g.drawImage(img, x, y, 64, 64, null)
      return
    }
    // Placeholder: a ball with the dex number.
    g.color = Color(232, 64, 48)
    g.fill(Ellipse2D.Double(x + 12.0, y + 12.0, 40.0, 40.0))
    g.color = Color.WHITE
    g.fillRect(x + 12, y + 32, 40, 20)
    g.color = Color.DARK_GRAY
    g.font = Font(Font.SANS_SERIF, Font.BOLD, 8)
    g.drawString("#$species", x + 20, y + 46)
  }

  private fun hpBox(g: Graphics2D, mon: BattleMon, x: Int, y: Int, enemy: Boolean) {
    val w = 104
    val h = if (enemy) 30 else 38
    g.color = Color(248, 248, 224)
    g.fill(
        RoundRectangle2D.Double(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(), 6.0, 6.0))
    g.color = Color(64, 64, 64)
    g.stroke = BasicStroke(1.2f)
    g.draw(
        RoundRectangle2D.Double(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(), 6.0, 6.0))
    g.font = Font(Font.SANS_SERIF, Font.BOLD, 8)
    g.drawString(mon.name, x + 5, y + 10)
    g.drawString("Lv${mon.level}", x + w - 26, y + 10)
    // HP bar
    val barX = x + 30
    val barY = y + 16
    val barW = 66
    g.font = Font(Font.SANS_SERIF, Font.BOLD, 6)
    g.color = Color(232, 160, 32)
    g.drawString("HP", x + 18, barY + 4)
    g.color = Color(80, 80, 80)
    g.fillRect(barX - 1, barY - 1, barW + 2, 6)
    g.color = Color(248, 248, 248)
    g.fillRect(barX, barY, barW, 4)
    val frac = if (mon.maxHp > 0) mon.hp.coerceAtLeast(0).toDouble() / mon.maxHp else 0.0
    g.color =
        when {
          frac > 0.5 -> Color(80, 208, 104)
          frac > 0.2 -> Color(240, 200, 40)
          else -> Color(232, 72, 56)
        }
    g.fillRect(barX, barY, (barW * frac).toInt(), 4)
    if (!enemy) {
      g.color = Color(64, 64, 64)
      g.font = Font(Font.MONOSPACED, Font.BOLD, 8)
      g.drawString("${mon.hp.coerceAtLeast(0)}/${mon.maxHp}", x + w - 46, y + 32)
    }
  }

  private fun textBox(g: Graphics2D, s: BattleState, ui: BattleUi) {
    g.color = Color(40, 80, 104)
    g.fillRect(0, 112, SCREEN_W, 48)
    g.color = Color(248, 248, 248)
    g.fill(RoundRectangle2D.Double(3.0, 115.0, SCREEN_W - 6.0, 42.0, 6.0, 6.0))
    g.color = Color(208, 96, 64)
    g.stroke = BasicStroke(1.5f)
    g.draw(RoundRectangle2D.Double(3.0, 115.0, SCREEN_W - 6.0, 42.0, 6.0, 6.0))

    val menuOpen = s.phase == BattlePhase.ACTION || s.phase == BattlePhase.FORCED_SWITCH
    val textW = if (menuOpen) 116 else 228
    g.font = Font(Font.SANS_SERIF, Font.PLAIN, 8)
    g.color = Color(72, 72, 72)
    val lines = s.log.flatMap { wrap(g, it, textW) }.takeLast(4)
    lines.forEachIndexed { i, line -> g.drawString(line, 9, 126 + i * 9) }
    if (!menuOpen) return

    // The command menu on the right.
    val mx = 124
    g.color = Color(248, 248, 248)
    g.fillRect(mx, 115, SCREEN_W - mx - 3, 42)
    g.color = Color(112, 104, 128)
    g.draw(RoundRectangle2D.Double(mx.toDouble(), 115.0, SCREEN_W - mx - 3.0, 42.0, 6.0, 6.0))
    val menu = if (s.phase == BattlePhase.FORCED_SWITCH) BattleMenu.PARTY else ui.menu
    val items: List<String> =
        when (menu) {
          BattleMenu.MAIN -> listOf("FIGHT", "BAG", "POKéMON", "RUN")
          BattleMenu.FIGHT ->
              s.me?.moves?.map { "${it.name.take(12)} ${it.pp}/${it.maxPp}" }.orEmpty()
          BattleMenu.PARTY ->
              s.party.map { "${it.name.take(10)} ${it.hp.coerceAtLeast(0)}/${it.maxHp}" }
        }
    val twoCols = menu == BattleMenu.MAIN
    g.font = Font(Font.SANS_SERIF, Font.BOLD, if (twoCols) 8 else 6)
    items.forEachIndexed { i, label ->
      val (cx, cy) =
          if (twoCols) (mx + 12 + (i % 2) * 52) to (130 + (i / 2) * 16)
          else (mx + 10 + (i % 2) * 56) to (124 + (i / 2) * 9 + (if (items.size <= 4) 6 else 0))
      g.color = Color(72, 72, 72)
      g.drawString(label, cx, cy)
      if (i == ui.cursor) {
        g.color = Color(224, 48, 48)
        g.fillPolygon(intArrayOf(cx - 7, cx - 2, cx - 7), intArrayOf(cy - 6, cy - 3, cy), 3)
      }
    }
  }

  private fun wrap(g: Graphics2D, text: String, width: Int): List<String> {
    val fm = g.fontMetrics
    val out = mutableListOf<String>()
    var line = ""
    for (word in text.split(' ')) {
      val next = if (line.isEmpty()) word else "$line $word"
      if (fm.stringWidth(next) > width && line.isNotEmpty()) {
        out += line
        line = word
      } else line = next
    }
    if (line.isNotEmpty()) out += line
    return out
  }

  private fun spriteFor(species: Int, front: Boolean): BufferedImage? {
    val key = "$species:$front"
    sprites[key]?.let {
      return it.takeIf { it.width > 1 }
    }
    val loaded = loadSprite(species, front) ?: BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    sprites[key] = loaded
    return loaded.takeIf { it.width > 1 }
  }

  private fun loadSprite(species: Int, front: Boolean): BufferedImage? {
    val name = de.fiereu.openmmo.client.net.BattleNames.species(species).lowercase()
    val candidates =
        linkedSetOf(
            name.replace("♀", "_f").replace("♂", "_m").replace(Regex("[^a-z0-9_]+"), "_").trim('_'),
            name.replace(Regex("[^a-z0-9]"), ""),
            name.replace(' ', '_'))
    for (game in listOf("pokefirered", "pokeemerald")) {
      for (folder in candidates) {
        val dir = File(decompRoot, "$game/graphics/pokemon/$folder")
        val png = File(dir, if (front) "front.png" else "back.png")
        if (!png.isFile) continue
        return runCatching { indexed(png, File(dir, "normal.pal")) }.getOrNull()
      }
    }
    return null
  }

  /** An indexed decomp sprite coloured with its JASC palette, index 0 transparent. */
  private fun indexed(png: File, pal: File): BufferedImage {
    val img = ImageIO.read(png)
    val colors = if (pal.isFile) PretData.readJascPalette(pal) else null
    val cm = img.colorModel as? java.awt.image.IndexColorModel
    val size = minOf(img.width, 64)
    val out = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val px = IntArray(4)
    for (y in 0 until size) for (x in 0 until size) {
      if (cm == null) {
        out.setRGB(x, y, img.getRGB(x, y))
        continue
      }
      val idx = img.raster.getPixel(x, y, px)[0]
      if (idx == 0) continue
      out.setRGB(x, y, colors?.getOrNull(idx and 0xF) ?: (cm.getRGB(idx) or (0xFF shl 24)))
    }
    return out
  }
}
