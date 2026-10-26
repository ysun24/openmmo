package de.fiereu.openmmo.client.gfx

import de.fiereu.openmmo.client.net.GameClient
import de.fiereu.openmmo.client.net.MarketClient
import de.fiereu.openmmo.client.net.MarketEntry
import de.fiereu.openmmo.client.net.MarketSortOrder
import de.fiereu.openmmo.client.net.SpeciesNames
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.swing.BorderFactory
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel

/**
 * The global market window: browse and buy, your listings with cancel, and a sell tab that lists a
 * party monster or a bag stack. Everything goes through [MarketClient]; the panel only redraws from
 * its snapshot.
 */
class MarketPanel(private val market: MarketClient, private val decomp: File) :
    JPanel(BorderLayout()) {
  private val money = JLabel()
  private val status = JLabel(" ")
  val tabs = JTabbedPane()

  // Browse.
  private val kind = JComboBox(arrayOf("Pokemon", "Items"))
  private val refId = JTextField(5)
  private val minPrice = JTextField(6)
  private val maxPrice = JTextField(6)
  private val sort = JComboBox(MarketSortOrder.entries.map { it.label }.toTypedArray())
  private val browseModel = ListingModel()
  private val browseTable = table(browseModel)
  private val pageLabel = JLabel()
  private var page = 0

  // Mine.
  private val mineModel = ListingModel()
  private val mineTable = table(mineModel)

  // Sell.
  private val partyModel = PartyModel()
  private val partyTable = table(partyModel)
  private val itemModel = ItemModel()
  private val itemTable = table(itemModel)
  private val sellPrice = JTextField("500", 8)
  private val sellQty = JTextField("1", 4)

  private val icons = ConcurrentHashMap<Int, ImageIcon?>()

  init {
    border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
    money.font = money.font.deriveFont(Font.BOLD, 14f)
    val header = JPanel(BorderLayout())
    header.add(
        JLabel("Global Market").apply { font = font.deriveFont(Font.BOLD, 16f) }, BorderLayout.WEST)
    header.add(money, BorderLayout.EAST)
    add(header, BorderLayout.NORTH)
    tabs.addTab("Browse", browseTab())
    tabs.addTab("My listings", mineTab())
    tabs.addTab("Sell", sellTab())
    tabs.addChangeListener {
      when (tabs.selectedIndex) {
        1 -> market.requestMine()
        2 -> market.requestInventory()
      }
    }
    add(tabs, BorderLayout.CENTER)
    add(status, BorderLayout.SOUTH)
    refresh()
  }

  /** Pull the latest [MarketClient.State] into the widgets. Call on the Swing thread. */
  fun refresh() {
    val s = market.state
    money.text = "Money: ${s.money?.let { "%,d".format(it) } ?: "?"}"
    browseModel.rows = s.browse
    mineModel.rows = s.mine
    partyModel.rows = s.party.sortedBy { it.containerSlot }.map { it.dexId to "Lv${it.level}" }
    itemModel.rows = s.items.map { it.itemId.toInt() to it.quantity }
    val pages = maxOf(1, (s.browseTotal + 19) / 20)
    pageLabel.text = "Page ${s.browsePage + 1}/$pages  (${s.browseTotal} listings)"
    s.log.lastOrNull()?.let { status.text = it }
  }

  private fun browseTab(): JPanel {
    val filters = JPanel(FlowLayout(FlowLayout.LEFT))
    filters.add(kind)
    filters.add(JLabel("Species/Item id"))
    filters.add(refId)
    filters.add(JLabel("Price"))
    filters.add(minPrice)
    filters.add(JLabel("-"))
    filters.add(maxPrice)
    filters.add(sort)
    filters.add(
        JButton("Search").apply {
          addActionListener {
            page = 0
            search()
          }
        })
    val actions = JPanel(FlowLayout(FlowLayout.LEFT))
    actions.add(
        JButton("< Prev").apply {
          addActionListener {
            if (page > 0) {
              page--
              search()
            }
          }
        })
    actions.add(pageLabel)
    actions.add(
        JButton("Next >").apply {
          addActionListener {
            page++
            search()
          }
        })
    actions.add(
        JButton("Buy selected").apply {
          addActionListener { selected(browseTable, browseModel)?.let { market.buy(it.listingId) } }
        })
    return JPanel(BorderLayout()).apply {
      add(filters, BorderLayout.NORTH)
      add(scroll(browseTable), BorderLayout.CENTER)
      add(actions, BorderLayout.SOUTH)
    }
  }

  private fun mineTab(): JPanel {
    val actions = JPanel(FlowLayout(FlowLayout.LEFT))
    actions.add(JButton("Refresh").apply { addActionListener { market.requestMine() } })
    actions.add(
        JButton("Cancel selected").apply {
          addActionListener {
            selected(mineTable, mineModel)?.let {
              market.cancel(it.listingId)
              market.requestMine()
            }
          }
        })
    return JPanel(BorderLayout()).apply {
      add(scroll(mineTable), BorderLayout.CENTER)
      add(actions, BorderLayout.SOUTH)
    }
  }

  private fun sellTab(): JPanel {
    val lists = JPanel(GridLayout(1, 2, 6, 0))
    lists.add(
        JPanel(BorderLayout()).apply {
          add(JLabel("Party"), BorderLayout.NORTH)
          add(scroll(partyTable), BorderLayout.CENTER)
        })
    lists.add(
        JPanel(BorderLayout()).apply {
          add(JLabel("Bag"), BorderLayout.NORTH)
          add(scroll(itemTable), BorderLayout.CENTER)
        })
    val actions = JPanel(FlowLayout(FlowLayout.LEFT))
    actions.add(JLabel("Price"))
    actions.add(sellPrice)
    actions.add(JLabel("Qty (items)"))
    actions.add(sellQty)
    actions.add(
        JButton("List pokemon").apply {
          addActionListener {
            val price = sellPrice.text.trim().toIntOrNull() ?: return@addActionListener
            val row = partyTable.selectedRow
            if (row >= 0) market.listPokemon(row, price)
          }
        })
    actions.add(
        JButton("List item").apply {
          addActionListener {
            val price = sellPrice.text.trim().toIntOrNull() ?: return@addActionListener
            val qty = sellQty.text.trim().toIntOrNull() ?: return@addActionListener
            val row = itemTable.selectedRow
            if (row >= 0) market.listItem(itemModel.rows[row].first, qty, price)
          }
        })
    return JPanel(BorderLayout()).apply {
      add(lists, BorderLayout.CENTER)
      add(actions, BorderLayout.SOUTH)
    }
  }

  fun search() {
    market.search(
        pokemon = kind.selectedIndex == 0,
        refId = refId.text.trim().toIntOrNull(),
        minPrice = minPrice.text.trim().toIntOrNull(),
        maxPrice = maxPrice.text.trim().toIntOrNull(),
        sort = MarketSortOrder.entries[sort.selectedIndex],
        page = page)
  }

  private fun selected(table: JTable, model: ListingModel): MarketEntry? =
      table.selectedRow.takeIf { it >= 0 }?.let { model.rows.getOrNull(it) }

  // Set the header explicitly: JTable only installs it when it is shown, which a headless
  // snapshot never does.
  private fun scroll(table: JTable): JScrollPane =
      JScrollPane(table).apply { setColumnHeaderView(table.tableHeader) }

  private fun table(model: AbstractTableModel): JTable =
      JTable(model).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        rowHeight = 34
        fillsViewportHeight = true
        preferredScrollableViewportSize = Dimension(600, 260)
        if (model is ListingModel || model is PartyModel) {
          columnModel.getColumn(0).maxWidth = 44
        }
      }

  /** The Emerald box icon, first frame, for national dex ids up to Celebi. */
  private fun icon(dexId: Int): ImageIcon? =
      icons.computeIfAbsent(dexId) {
        val folder = SpeciesNames.folder(dexId) ?: return@computeIfAbsent null
        val file = File(decomp, "pokeemerald/graphics/pokemon/$folder/icon.png")
        if (!file.isFile) return@computeIfAbsent null
        runCatching {
              val sheet = PretData.readSprite(file)
              val frame = sheet.getSubimage(0, 0, 32, 32)
              ImageIcon(frame.getScaledInstance(32, 32, Image.SCALE_FAST))
            }
            .getOrNull()
      }

  private inner class ListingModel : AbstractTableModel() {
    var rows: List<MarketEntry> = emptyList()
      set(value) {
        if (field != value) {
          field = value
          fireTableDataChanged()
        }
      }

    private val cols = arrayOf("", "Listing", "What", "Qty", "Price", "Expires in", "Seller")

    override fun getRowCount() = rows.size

    override fun getColumnCount() = cols.size

    override fun getColumnName(c: Int) = cols[c]

    override fun getColumnClass(c: Int): Class<*> =
        if (c == 0) ImageIcon::class.java else Any::class.java

    override fun getValueAt(r: Int, c: Int): Any? {
      val e = rows[r]
      return when (c) {
        0 -> if (e.isPokemon) icon(e.refId) else null
        1 -> "#${e.listingId}"
        2 -> e.describe()
        3 -> e.quantity
        4 -> "%,d".format(e.price)
        5 -> remaining(e.expiresAtEpoch)
        else ->
            if (e.sellerId != null && e.sellerId == market.selfId) "you"
            else if (e.sellerId == null) "" else "other"
      }
    }
  }

  private inner class PartyModel : AbstractTableModel() {
    var rows: List<Pair<Int, String>> = emptyList()
      set(value) {
        if (field != value) {
          field = value
          fireTableDataChanged()
        }
      }

    private val cols = arrayOf("", "Slot", "Pokemon", "Level")

    override fun getRowCount() = rows.size

    override fun getColumnCount() = cols.size

    override fun getColumnName(c: Int) = cols[c]

    override fun getColumnClass(c: Int): Class<*> =
        if (c == 0) ImageIcon::class.java else Any::class.java

    override fun getValueAt(r: Int, c: Int): Any? =
        when (c) {
          0 -> icon(rows[r].first)
          1 -> r
          2 -> SpeciesNames.name(rows[r].first)
          else -> rows[r].second
        }
  }

  private class ItemModel : AbstractTableModel() {
    var rows: List<Pair<Int, Int>> = emptyList()
      set(value) {
        if (field != value) {
          field = value
          fireTableDataChanged()
        }
      }

    override fun getRowCount() = rows.size

    override fun getColumnCount() = 2

    override fun getColumnName(c: Int) = if (c == 0) "Item id" else "Quantity"

    override fun getValueAt(r: Int, c: Int): Any = if (c == 0) rows[r].first else rows[r].second
  }

  companion object {
    fun remaining(epoch: Long): String {
      val d = Duration.between(Instant.now(), Instant.ofEpochSecond(epoch))
      if (d.isNegative) return "expired"
      return if (d.toDays() > 0) "${d.toDays()}d ${d.toHoursPart()}h"
      else "${d.toHours()}h ${d.toMinutesPart()}m"
    }

    /**
     * Renders every tab of [panel] into one image, top to bottom. Works headless: the components
     * are laid out and painted without ever being shown.
     */
    fun snapshot(panel: MarketPanel, width: Int = 860, height: Int = 440): BufferedImage {
      val out = BufferedImage(width, height * panel.tabs.tabCount, BufferedImage.TYPE_INT_RGB)
      val g = out.createGraphics()
      g.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
      for (i in 0 until panel.tabs.tabCount) {
        panel.tabs.selectedIndex = i
        panel.refresh()
        panel.setSize(width, height)
        layoutAll(panel)
        val tile = g.create(0, i * height, width, height)
        tile.color = Color.WHITE
        tile.fillRect(0, 0, width, height)
        panel.printAll(tile)
        tile.dispose()
      }
      g.dispose()
      return out
    }

    private fun layoutAll(c: Component) {
      c.doLayout()
      if (c is Container) c.components.forEach(::layoutAll)
    }
  }
}

/** The market in its own window, opened from the game window with M. */
class MarketWindow(private val client: GameClient, private val decomp: File) {
  fun show() {
    SwingUtilities.invokeLater {
      val panel = MarketPanel(client.market, decomp)
      val frame = JFrame("OpenMMO - Global Market")
      frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
      frame.contentPane.add(panel)
      frame.preferredSize = Dimension(880, 520)
      frame.pack()
      frame.setLocationRelativeTo(null)
      frame.isVisible = true
      client.market.requestInventory()
      panel.search()
      javax.swing.Timer(250) { panel.refresh() }.start()
    }
  }
}
