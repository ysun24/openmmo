package de.fiereu.openmmo.client.net

import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.net.game.packets.ChatMessageSendPacket
import de.fiereu.openmmo.net.game.packets.CreateMarketListingPacket
import de.fiereu.openmmo.net.game.packets.CurrencyBalancePacket
import de.fiereu.openmmo.net.game.packets.gtl.GtlConfirmPurchasePacket
import de.fiereu.openmmo.net.game.packets.gtl.GtlFilterKind
import de.fiereu.openmmo.net.game.packets.gtl.GtlItemListing
import de.fiereu.openmmo.net.game.packets.gtl.GtlListKind
import de.fiereu.openmmo.net.game.packets.gtl.GtlListingCancelPacket
import de.fiereu.openmmo.net.game.packets.gtl.GtlPokemonListing
import de.fiereu.openmmo.net.game.packets.gtl.GtlRawFilter
import de.fiereu.openmmo.net.game.packets.gtl.GtlSearchFilter
import de.fiereu.openmmo.net.game.packets.gtl.GtlSearchListing
import de.fiereu.openmmo.net.game.packets.gtl.GtlSearchPagePacket
import de.fiereu.openmmo.net.game.packets.gtl.GtlSearchPageRequestPacket
import de.fiereu.openmmo.net.game.packets.gtl.GtlSpeciesFilter
import de.fiereu.openmmo.net.game.packets.market.MarketAction
import de.fiereu.openmmo.net.game.packets.market.MarketInventoryPacket
import de.fiereu.openmmo.net.game.packets.market.MarketInventoryRequestPacket
import de.fiereu.openmmo.net.game.packets.market.MarketItemStack
import de.fiereu.openmmo.net.game.packets.market.MarketResultPacket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Market wire values shared with the server's MarketService. */
const val MARKET_ENTRY_POKEMON: Byte = 0
const val MARKET_ENTRY_ITEM: Byte = 1

enum class MarketSortOrder(val wire: Byte, val label: String) {
  PRICE_ASC(0, "Price: low to high"),
  PRICE_DESC(1, "Price: high to low"),
  NEWEST(2, "Newest first"),
}

/** One market row the way the UI and the CLI show it. */
data class MarketEntry(
    val listingId: Long,
    val isPokemon: Boolean,
    val refId: Int,
    val level: Int?,
    val nickname: String?,
    val quantity: Int,
    val price: Int,
    val expiresAtEpoch: Long,
    /** Only known for monster rows, whose record carries the owner. */
    val sellerId: Long?,
) {
  fun describe(): String =
      if (isPokemon) "${SpeciesNames.name(refId)} Lv$level" + (nickname?.let { " \"$it\"" } ?: "")
      else "${quantity}x item $refId"
}

/**
 * The client side of the global market. Requests go out through [send]; replies are fed in by the
 * game connection's handler. Everything a reply changes is published as a snapshot and through
 * [onChange], and results and pages are also queued so a script can wait for them.
 */
class MarketClient(private val send: (Any) -> Unit, private val onChange: () -> Unit) {
  data class State(
      val money: Int? = null,
      val party: List<Pokemon> = emptyList(),
      val pcCount: Int = 0,
      val items: List<MarketItemStack> = emptyList(),
      val browse: List<MarketEntry> = emptyList(),
      val browseTotal: Int = 0,
      val browsePage: Int = 0,
      val mine: List<MarketEntry> = emptyList(),
      val log: List<String> = emptyList(),
  )

  @Volatile
  var state = State()
    private set

  /** Set once the character is known, to tell own monster listings apart. */
  @Volatile var selfId: Long? = null

  private val requestIds = AtomicInteger(1)
  val results = LinkedBlockingQueue<MarketResultPacket>()
  val pages = LinkedBlockingQueue<GtlSearchPagePacket>()
  val inventories = LinkedBlockingQueue<MarketInventoryPacket>()

  // Actions.

  fun requestInventory() = send(MarketInventoryRequestPacket())

  /**
   * Lists the monster in party [slot] (0-based, in slot order). False when there is no such slot.
   */
  fun listPokemon(slot: Int, price: Int): Boolean {
    val mon = state.party.sortedBy { it.containerSlot }.getOrNull(slot) ?: return false
    send(CreateMarketListingPacket(MARKET_ENTRY_POKEMON, mon.id, price, 1))
    return true
  }

  fun listItem(itemId: Int, quantity: Int, price: Int) =
      send(CreateMarketListingPacket(MARKET_ENTRY_ITEM, itemId.toLong(), price, quantity.toShort()))

  fun search(
      pokemon: Boolean,
      refId: Int? = null,
      minPrice: Int? = null,
      maxPrice: Int? = null,
      sort: MarketSortOrder = MarketSortOrder.PRICE_ASC,
      page: Int = 0,
  ) {
    val filters =
        buildList<GtlSearchFilter> {
          refId?.let { add(GtlSpeciesFilter(listOf(it.toShort()))) }
          minPrice?.let { add(GtlRawFilter(GtlFilterKind.MIN_PRICE, le(it))) }
          maxPrice?.let { add(GtlRawFilter(GtlFilterKind.MAX_PRICE, le(it))) }
        }
    send(
        GtlSearchPageRequestPacket(
            nextRequestId(),
            if (pokemon) GtlListKind.POKEMON else GtlListKind.ITEM,
            sort.wire,
            page.toShort(),
            filters))
  }

  fun requestMine() =
      send(
          GtlSearchPageRequestPacket(
              nextRequestId(), GtlListKind.OWN_LISTINGS, MarketSortOrder.NEWEST.wire, 0))

  fun buy(listingId: Long) = send(GtlConfirmPurchasePacket(listingId, 1))

  fun cancel(listingId: Long) = send(GtlListingCancelPacket(listingId.toString()))

  /** Sends a slash command such as `/givemon 25 10` the way the game's chat box does. */
  fun chatCommand(text: String) = send(ChatMessageSendPacket(0, text, null))

  // Replies, called on the netty thread.

  fun onInventory(p: MarketInventoryPacket) {
    state =
        state.copy(money = p.money, party = p.party, pcCount = p.pcCount.toInt(), items = p.items)
    inventories.offer(p)
    onChange()
  }

  fun onBalance(p: CurrencyBalancePacket) {
    state = state.copy(money = p.primaryBalance)
    onChange()
  }

  fun onResult(p: MarketResultPacket) {
    val line = "${p.action} ${if (p.success) "ok" else "refused"}: ${p.message}"
    var next = state.copy(log = (state.log + line).takeLast(50))
    // A listing that was bought, cancelled or sold is gone; drop it so the tables don't show it.
    if (p.success && p.action in GONE_ACTIONS) {
      next =
          next.copy(
              browse = next.browse.filterNot { it.listingId == p.listingId },
              mine = next.mine.filterNot { it.listingId == p.listingId })
    }
    state = next
    results.offer(p)
    onChange()
  }

  fun onPage(p: GtlSearchPagePacket) {
    val rows = p.listings.map(::toEntry)
    state =
        if (p.listKind == GtlListKind.OWN_LISTINGS) state.copy(mine = rows)
        else state.copy(browse = rows, browseTotal = p.totalMatches, browsePage = p.page.toInt())
    pages.offer(p)
    onChange()
  }

  // Script helpers: wait for the next reply of a kind.

  fun awaitResult(timeoutMs: Long = 5000): MarketResultPacket? =
      results.poll(timeoutMs, TimeUnit.MILLISECONDS)

  fun awaitPage(timeoutMs: Long = 5000): GtlSearchPagePacket? =
      pages.poll(timeoutMs, TimeUnit.MILLISECONDS)

  fun awaitInventory(timeoutMs: Long = 5000): MarketInventoryPacket? =
      inventories.poll(timeoutMs, TimeUnit.MILLISECONDS)

  fun drain() {
    results.clear()
    pages.clear()
    inventories.clear()
  }

  private fun nextRequestId(): Byte = (requestIds.getAndIncrement() and 0x7F).toByte()

  private fun le(v: Int): ByteArray =
      ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

  private fun toEntry(l: GtlSearchListing): MarketEntry =
      when (l) {
        is GtlPokemonListing ->
            MarketEntry(
                l.listingId,
                true,
                l.pokemon?.dexId ?: 0,
                l.pokemon?.level?.toInt(),
                l.pokemon?.nickname?.takeIf { it.isNotEmpty() },
                l.quantity.toInt(),
                l.price,
                l.expiresAt.toLong(),
                l.pokemon?.ownerId,
            )
        is GtlItemListing ->
            MarketEntry(
                l.listingId,
                false,
                l.itemId.toInt(),
                null,
                null,
                l.quantity.toInt(),
                l.price,
                l.expiresAt.toLong(),
                null,
            )
      }
}

/** Species names in national dex order, read from the pret Emerald decomp when it is there. */
object SpeciesNames {
  @Volatile private var names: List<String>? = null

  fun load(decompRoot: java.io.File) {
    val f = java.io.File(decompRoot, "pokeemerald/src/data/text/species_names.h")
    if (!f.isFile) return
    val pattern = Regex("""\[SPECIES_\w+]\s*=\s*_\("([^"]*)"\)""")
    names = f.readLines().mapNotNull { line -> pattern.find(line)?.groupValues?.get(1) }
  }

  /** Emerald's species order only matches the national dex up to Celebi. */
  fun name(dexId: Int): String {
    val list = names
    if (list != null && dexId in 1..251 && dexId < list.size) {
      return list[dexId].lowercase().replaceFirstChar { it.uppercase() }
    }
    return "#$dexId"
  }

  fun folder(dexId: Int): String? =
      if (names != null && dexId in 1..251)
          name(dexId)
              .lowercase()
              .replace("♀", "_f")
              .replace("♂", "_m")
              .replace(".", "")
              .replace("'", "")
              .replace(" ", "_")
      else null
}

private val GONE_ACTIONS = setOf(MarketAction.BUY, MarketAction.CANCEL, MarketAction.SOLD)
