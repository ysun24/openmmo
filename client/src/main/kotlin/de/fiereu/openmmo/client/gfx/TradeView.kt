package de.fiereu.openmmo.client.gfx

import de.fiereu.openmmo.client.net.GameNames
import de.fiereu.openmmo.net.game.packets.trade.TradeMonView
import de.fiereu.openmmo.net.game.packets.trade.TradeOfferView
import de.fiereu.openmmo.net.game.packets.trade.TradePhase
import de.fiereu.openmmo.net.game.packets.trade.TradeStage
import de.fiereu.openmmo.net.game.packets.trade.TradeStatePacket
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

private val PANEL = Color(248, 248, 240)
private val BORDER = Color(64, 72, 96)
private val HEADER = Color(96, 120, 200)
private val INK = Color(32, 32, 40)
private val MUTED = Color(120, 120, 130)
private val SLOT = Color(224, 228, 236)

/**
 * Draws the trade window: both offers side by side with the pret front sprites, each side's stage,
 * and the last server message. Pure painting, so the Swing window and the headless snapshot share
 * it.
 */
class TradeView(private val decomp: File) {
  private val sprites = ConcurrentHashMap<Int, BufferedImage?>()

  fun paint(g: Graphics2D, x: Int, y: Int, w: Int, h: Int, state: TradeStatePacket) {
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
    g.color = PANEL
    g.fillRoundRect(x, y, w, h, 16, 16)
    g.color = BORDER
    g.stroke = BasicStroke(3f)
    g.drawRoundRect(x, y, w, h, 16, 16)

    g.color = HEADER
    g.fillRoundRect(x + 6, y + 6, w - 12, 30, 10, 10)
    g.color = Color.WHITE
    g.font = Font(Font.SANS_SERIF, Font.BOLD, 18)
    g.drawString(title(state), x + 16, y + 28)

    val body = y + 46
    when (state.phase) {
      TradePhase.OPEN -> {
        val colW = (w - 30) / 2
        column(g, x + 10, body, colW, h - 80, "Your offer", state.mine, state.myStage)
        column(
            g,
            x + 20 + colW,
            body,
            colW,
            h - 80,
            "${state.partnerName}'s offer",
            state.theirs,
            state.theirStage)
      }
      TradePhase.REQUEST_RECEIVED ->
          centered(g, x, body + 60, w, "${state.partnerName} wants to trade with you.")
      TradePhase.REQUEST_SENT ->
          centered(g, x, body + 60, w, "Waiting for ${state.partnerName} to accept...")
      TradePhase.COMPLETED -> centered(g, x, body + 60, w, "Trade complete!")
      TradePhase.CLOSED -> centered(g, x, body + 60, w, "No trade.")
    }

    g.font = Font(Font.SANS_SERIF, Font.PLAIN, 14)
    g.color = INK
    if (state.message.isNotEmpty()) g.drawString(state.message, x + 14, y + h - 32)
    g.color = MUTED
    g.drawString(hint(state), x + 14, y + h - 12)
  }

  private fun title(s: TradeStatePacket): String =
      when (s.phase) {
        TradePhase.OPEN -> "Trade with ${s.partnerName}"
        TradePhase.REQUEST_SENT,
        TradePhase.REQUEST_RECEIVED -> "Trade request"
        TradePhase.COMPLETED -> "Trade with ${s.partnerName}"
        TradePhase.CLOSED -> "Trade"
      }

  private fun hint(s: TradeStatePacket): String =
      when (s.phase) {
        TradePhase.OPEN ->
            when (s.myStage) {
              TradeStage.NONE -> "Pick monsters and items, then Ready.  Cancel ends the trade."
              TradeStage.READY ->
                  if (s.theirStage == TradeStage.NONE) "Waiting for ${s.partnerName} to be ready."
                  else "Both ready: Confirm to trade."
              TradeStage.CONFIRMED -> "Waiting for ${s.partnerName} to confirm."
            }
        TradePhase.REQUEST_RECEIVED -> "Accept or Decline."
        else -> ""
      }

  private fun column(
      g: Graphics2D,
      x: Int,
      y: Int,
      w: Int,
      h: Int,
      label: String,
      offer: TradeOfferView,
      stage: TradeStage,
  ) {
    g.color = INK
    g.font = Font(Font.SANS_SERIF, Font.BOLD, 15)
    g.drawString(label, x + 4, y + 14)
    stageBadge(g, x + w - 104, y, stage)

    // Six monster cells, three by two.
    val cell = (w - 8) / 3
    val cellH = 80
    for (i in 0 until 6) {
      val cx = x + (i % 3) * cell + 2
      val cy = y + 24 + (i / 3) * (cellH + 4)
      g.color = SLOT
      g.fillRoundRect(cx, cy, cell - 4, cellH, 8, 8)
      offer.mons.getOrNull(i)?.let { mon(g, cx, cy, cell - 4, cellH, it) }
    }

    var ty = y + 24 + 2 * (cellH + 4) + 16
    g.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
    g.color = INK
    val lines =
        offer.items.map { "${it.quantity} x ${GameNames.item(it.itemId)}" } +
            listOfNotNull(offer.money.takeIf { it > 0 }?.let { "Money: $it" })
    if (lines.isEmpty()) {
      g.color = MUTED
      g.drawString("No items", x + 4, ty)
    }
    for (line in lines.take(((h - (ty - y)) / 16).coerceAtLeast(1))) {
      g.drawString(line, x + 4, ty)
      ty += 16
    }
  }

  private fun mon(g: Graphics2D, x: Int, y: Int, w: Int, h: Int, mon: TradeMonView) {
    sprite(mon.dexId)?.let { g.drawImage(it, x + (w - 56) / 2, y + 2, 56, 56, null) }
        ?: run {
          g.color = MUTED
          g.drawString("#${mon.dexId}", x + w / 2 - 12, y + 36)
        }
    g.color = INK
    g.font = Font(Font.SANS_SERIF, Font.BOLD, 11)
    val name = mon.nickname.ifEmpty { GameNames.species(mon.dexId) }
    g.drawString(name.take(12), x + 4, y + h - 16)
    g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
    g.drawString("Lv${mon.level}${if (mon.shiny) " *" else ""}", x + 4, y + h - 4)
  }

  private fun stageBadge(g: Graphics2D, x: Int, y: Int, stage: TradeStage) {
    val (bg, text) =
        when (stage) {
          TradeStage.NONE -> Color(200, 200, 205) to "Choosing"
          TradeStage.READY -> Color(240, 200, 64) to "Ready"
          TradeStage.CONFIRMED -> Color(88, 184, 96) to "Confirmed"
        }
    g.color = bg
    g.fillRoundRect(x, y, 100, 20, 10, 10)
    g.color = INK
    g.font = Font(Font.SANS_SERIF, Font.BOLD, 12)
    val tw = g.fontMetrics.stringWidth(text)
    g.drawString(text, x + (100 - tw) / 2, y + 15)
  }

  private fun centered(g: Graphics2D, x: Int, y: Int, w: Int, text: String) {
    g.color = INK
    g.font = Font(Font.SANS_SERIF, Font.PLAIN, 18)
    val tw = g.fontMetrics.stringWidth(text)
    g.drawString(text, x + (w - tw) / 2, y)
  }

  /** The pret front sprite, palette index 0 made transparent. Null when there is none. */
  fun sprite(dexId: Int): BufferedImage? = sprites.computeIfAbsent(dexId) { loadSprite(it) }

  private fun loadSprite(dexId: Int): BufferedImage? {
    val folder = folderName(GameNames.species(dexId))
    val file =
        listOf("pokeemerald", "pokefirered")
            .map { File(decomp, "$it/graphics/pokemon/$folder/front.png") }
            .firstOrNull { it.isFile } ?: return null
    val raw = runCatching { ImageIO.read(file) }.getOrNull() ?: return null
    val size = minOf(raw.width, raw.height, 64)
    val out = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val model = raw.colorModel as? IndexColorModel
    for (py in 0 until size) for (px in 0 until size) {
      val transparent = model != null && raw.raster.getSample(px, py, 0) == 0
      out.setRGB(px, py, if (transparent) 0 else raw.getRGB(px, py) or (0xFF shl 24))
    }
    return out
  }

  private fun folderName(species: String): String =
      species
          .lowercase()
          .replace("♀", "_f")
          .replace("♂", "_m")
          .replace(". ", "_")
          .replace("'", "")
          .replace(".", "")
          .replace(" ", "_")
          .replace("-", "_")
}
