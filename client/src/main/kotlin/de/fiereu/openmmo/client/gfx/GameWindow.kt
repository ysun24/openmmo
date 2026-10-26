package de.fiereu.openmmo.client.gfx

import de.fiereu.openmmo.client.net.GameClient
import de.fiereu.openmmo.common.enums.Direction
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * The playable window. Arrow keys or WASD walk (hold Shift to run), Enter opens the chat box, Enter
 * again sends, Escape closes it. T opens the trade window, which also opens on a trade request.
 */
class GameWindow(
    private val client: GameClient,
    private val renderer: WorldRenderer,
    private val scale: Int = 3,
    private val battleScreen: BattleScreen? = null,
    /** Draws the trade window; without it there is no trade window. */
    private val tradeView: TradeView? = null,
) {
  private var tradeWindow: TradeWindow? = null
  private val frameBuf = WorldRenderer.newFrame()
  private val battleInput = BattleInput(client.battle)
  private val battleBuf = battleScreen?.newFrame()
  private val held = linkedSetOf<Direction>()
  private var running = false
  private var lastStep = 0L

  private val canvas =
      object : JPanel() {
        init {
          preferredSize = Dimension(SCREEN_W * scale, SCREEN_H * scale)
          isFocusable = true
        }

        override fun paintComponent(g: Graphics) {
          val battle = client.battle.snapshot()
          if (battleScreen != null && battleBuf != null && battle.active) {
            g.drawImage(
                battleScreen.render(battle, battleInput.ui, battleBuf), 0, 0, width, height, null)
            return
          }
          renderer.render(client.world.snapshot(), frameBuf)
          g.drawImage(frameBuf, 0, 0, width, height, null)
        }
      }

  private val chatBox = JTextField().apply { isVisible = false }

  fun show() {
    SwingUtilities.invokeLater {
      val frame = JFrame("OpenMMO - own client")
      frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
      frame.layout = BorderLayout()
      frame.add(canvas, BorderLayout.CENTER)
      frame.add(chatBox, BorderLayout.SOUTH)
      frame.pack()
      frame.setLocationRelativeTo(null)
      frame.addWindowListener(
          object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) = client.disconnect()
          })
      canvas.addKeyListener(keys)
      tradeWindow = tradeView?.let { TradeWindow(frame, client.trade, it) }
      chatBox.addActionListener {
        val text = chatBox.text.trim()
        // Slash commands go through the typed-chat packet, which is the only one the server parses.
        if (text.startsWith("/")) client.trade.command(text)
        else if (text.isNotEmpty()) client.say(text)
        closeChat(frame)
      }
      chatBox.addKeyListener(
          object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
              if (e.keyCode == KeyEvent.VK_ESCAPE) closeChat(frame)
            }
          })
      frame.isVisible = true
      canvas.requestFocusInWindow()
      // ~30 fps repaint plus the walk tick; a step every 250 ms is roughly the GBA walk speed.
      Timer(33) {
            tickWalk()
            canvas.repaint()
          }
          .start()
    }
  }

  private fun closeChat(frame: JFrame) {
    chatBox.text = ""
    chatBox.isVisible = false
    frame.revalidate()
    canvas.requestFocusInWindow()
  }

  private fun tickWalk() {
    val dir = held.lastOrNull() ?: return
    val now = System.currentTimeMillis()
    val interval = if (running) 130 else 250
    if (now - lastStep < interval) return
    lastStep = now
    client.step(dir, running)
  }

  private val keys =
      object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
          running = e.isShiftDown
          if (battleInput.keyPressed(e)) {
            held.clear()
            return
          }
          if (e.keyCode == KeyEvent.VK_ENTER) {
            chatBox.isVisible = true
            SwingUtilities.getWindowAncestor(canvas)?.revalidate()
            chatBox.requestFocusInWindow()
            held.clear()
            return
          }
          if (e.keyCode == KeyEvent.VK_T) tradeWindow?.show()
          dirOf(e.keyCode)?.let { held.add(it) }
        }

        override fun keyReleased(e: KeyEvent) {
          running = e.isShiftDown
          dirOf(e.keyCode)?.let { held.remove(it) }
        }
      }

  private fun dirOf(code: Int): Direction? =
      when (code) {
        KeyEvent.VK_UP,
        KeyEvent.VK_W -> Direction.UP
        KeyEvent.VK_DOWN,
        KeyEvent.VK_S -> Direction.DOWN
        KeyEvent.VK_LEFT,
        KeyEvent.VK_A -> Direction.LEFT
        KeyEvent.VK_RIGHT,
        KeyEvent.VK_D -> Direction.RIGHT
        else -> null
      }
}
