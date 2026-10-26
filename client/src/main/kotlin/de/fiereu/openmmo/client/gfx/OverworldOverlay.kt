package de.fiereu.openmmo.client.gfx

import de.fiereu.openmmo.client.world.OverworldState
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints

/**
 * GBA-style windows over the field: the text box at the bottom of the screen with its choice menu
 * in the corner, and the mart's buy list. Drawn at GBA resolution like the rest of the frame.
 */
class OverworldOverlay {
  fun draw(g: Graphics2D, ui: OverworldState.View) {
    g.setRenderingHint(
        RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
    ui.shop?.let { shop ->
      val rows = shop.offers.size.coerceAtMost(7)
      val h = 12 + rows * 13 + 6
      window(g, 104, 2, 134, h)
      shop.offers.take(rows).forEachIndexed { i, o ->
        val y = 16 + i * 13
        if (i == shop.cursor) cursor(g, 110, y)
        gbaText(g, o.name.uppercase(), 118, y)
        val price = "¥${o.price}"
        g.font = FONT
        gbaText(g, price, 232 - g.fontMetrics.stringWidth(price), y)
      }
      window(g, 2, 2, 98, 28)
      gbaText(g, "MONEY", 10, 14)
      val money = "¥${ui.money ?: 0}"
      g.font = FONT
      gbaText(g, money, 92 - g.fontMetrics.stringWidth(money), 25)
      if (shop.status.isNotEmpty()) {
        window(g, 2, SCREEN_H - 30, SCREEN_W - 4, 28)
        gbaText(g, shop.status, 12, SCREEN_H - 12)
      }
    }
    ui.dialog?.let { d ->
      // The standard message box: 2 lines, 2 px frame, bottom of the screen.
      window(g, 2, SCREEN_H - 46, SCREEN_W - 4, 44)
      val lines = d.pages.getOrElse(d.page) { emptyList() }
      lines.forEachIndexed { i, line -> gbaText(g, line, 12, SCREEN_H - 28 + i * 16) }
      if (d.choices.isNotEmpty() && d.onLastPage) {
        g.font = FONT
        val w = (d.choices.maxOf { g.fontMetrics.stringWidth(it) } + 22).coerceAtLeast(48)
        val h = d.choices.size * 14 + 10
        val x = SCREEN_W - w - 2
        val y = SCREEN_H - 48 - h
        window(g, x, y, w, h)
        d.choices.forEachIndexed { i, c ->
          val cy = y + 15 + i * 14
          if (i == d.cursor) cursor(g, x + 6, cy)
          gbaText(g, c, x + 14, cy)
        }
      } else if (!d.onLastPage || d.choices.isEmpty()) {
        // The red "more" arrow in the corner.
        g.color = Color(0xE0, 0x40, 0x30)
        val ax = SCREEN_W - 16
        val ay = SCREEN_H - 14
        g.fillPolygon(intArrayOf(ax, ax + 7, ax + 3), intArrayOf(ay, ay, ay + 4), 3)
      }
    }
    if (ui.transitioning) {
      g.color = Color(0, 0, 0, 255)
      g.fillRect(0, 0, SCREEN_W, SCREEN_H)
    }
  }

  private fun window(g: Graphics2D, x: Int, y: Int, w: Int, h: Int) {
    g.color = Color(0x40, 0x48, 0x58)
    g.fillRoundRect(x, y, w, h, 6, 6)
    g.color = Color(0xA0, 0xB8, 0xD0)
    g.fillRoundRect(x + 1, y + 1, w - 2, h - 2, 5, 5)
    g.color = Color(0xF8, 0xF8, 0xF8)
    g.fillRect(x + 3, y + 3, w - 6, h - 6)
  }

  private fun cursor(g: Graphics2D, x: Int, baseline: Int) {
    g.color = Color(0x48, 0x48, 0x48)
    g.fillPolygon(intArrayOf(x, x, x + 5), intArrayOf(baseline - 8, baseline, baseline - 4), 3)
  }

  /** Dark grey text with the light shadow the GBA font uses. */
  private fun gbaText(g: Graphics2D, s: String, x: Int, y: Int) {
    g.font = FONT
    g.color = Color(0xD0, 0xD0, 0xC8)
    g.drawString(s, x + 1, y + 1)
    g.color = Color(0x50, 0x50, 0x50)
    g.drawString(s, x, y)
  }

  private companion object {
    val FONT = Font(Font.SANS_SERIF, Font.BOLD, 10)
  }
}
