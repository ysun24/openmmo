package de.fiereu.openmmo.client.gfx

import de.fiereu.openmmo.client.net.GameNames
import de.fiereu.openmmo.client.net.TradeClient
import de.fiereu.openmmo.net.game.packets.trade.TradePhase
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.Timer

const val TRADE_VIEW_W = 640
const val TRADE_VIEW_H = 360

/**
 * The trade window of the Swing client. It opens on its own when a request arrives or is sent, and
 * drives [TradeClient]: party buttons toggle monsters, a field offers items or money, and the
 * bottom row accepts, readies, confirms or cancels. Everything shown comes from the server's state.
 */
class TradeWindow(owner: JFrame, private val trade: TradeClient, private val view: TradeView) {
  private val dialog = JDialog(owner, "Trade", false)
  private var seenUpdates = -1

  private val canvas =
      object : JPanel() {
        init {
          preferredSize = Dimension(TRADE_VIEW_W, TRADE_VIEW_H)
        }

        override fun paintComponent(g: Graphics) {
          super.paintComponent(g)
          view.paint(g as Graphics2D, 0, 0, width - 1, height - 1, trade.state)
        }
      }

  private val partyRow = JPanel(GridLayout(1, 6, 4, 4))
  private val partner = JTextField(10)
  private val itemId = JTextField("5017", 5)
  private val itemQty = JTextField("1", 3)
  private val money = JTextField("0", 6)

  private val accept = JButton("Accept").apply { addActionListener { trade.accept() } }
  private val decline = JButton("Decline").apply { addActionListener { trade.decline() } }
  private val ready = JButton("Ready").apply { addActionListener { trade.ready() } }
  private val confirm = JButton("Confirm").apply { addActionListener { trade.confirm() } }
  private val cancel = JButton("Cancel").apply { addActionListener { trade.cancel() } }

  init {
    val offerRow =
        JPanel(FlowLayout(FlowLayout.LEFT)).apply {
          add(JLabel("Item id"))
          add(itemId)
          add(JLabel("qty"))
          add(itemQty)
          add(
              JButton("Offer item").apply {
                addActionListener {
                  trade.offerItem(itemId.text.toIntOrNull() ?: 0, itemQty.text.toIntOrNull() ?: 0)
                }
              })
          add(JLabel("Money"))
          add(money)
          add(
              JButton("Offer money").apply {
                addActionListener { trade.offerMoney(money.text.toIntOrNull() ?: 0) }
              })
        }
    val actions =
        JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
          add(JLabel("Trade with"))
          add(partner)
          add(JButton("Request").apply { addActionListener { trade.request(partner.text.trim()) } })
          add(accept)
          add(decline)
          add(ready)
          add(confirm)
          add(cancel)
        }
    val south =
        JPanel(BorderLayout()).apply {
          add(partyRow, BorderLayout.NORTH)
          add(offerRow, BorderLayout.CENTER)
          add(actions, BorderLayout.SOUTH)
        }
    dialog.layout = BorderLayout()
    dialog.add(canvas, BorderLayout.CENTER)
    dialog.add(south, BorderLayout.SOUTH)
    dialog.pack()
    Timer(100) { refresh() }.start()
  }

  /** Opens the window by hand, for starting a trade from it. */
  fun show() {
    dialog.isVisible = true
  }

  private fun refresh() {
    val state = trade.state
    if (trade.updates != seenUpdates) {
      seenUpdates = trade.updates
      if (state.phase != TradePhase.CLOSED && !dialog.isVisible) dialog.isVisible = true
      rebuildParty()
    }
    val open = state.phase == TradePhase.OPEN
    accept.isEnabled = state.phase == TradePhase.REQUEST_RECEIVED
    decline.isEnabled = state.phase == TradePhase.REQUEST_RECEIVED
    ready.isEnabled = open
    confirm.isEnabled = open
    cancel.isEnabled = state.phase != TradePhase.CLOSED && state.phase != TradePhase.COMPLETED
    canvas.repaint()
  }

  private fun rebuildParty() {
    partyRow.removeAll()
    val offered = trade.state.mine.mons.map { it.partySlot }.toSet()
    trade.party.forEachIndexed { slot, mon ->
      val label =
          "${if (slot in offered) "[x] " else ""}${GameNames.species(mon.dexId)} L${mon.level}"
      partyRow.add(JButton(label).apply { addActionListener { trade.toggleMon(slot) } })
    }
    partyRow.revalidate()
    partyRow.repaint()
  }
}
