package de.fiereu.openmmo.client.world

/** A map the server has sent, kept so seamless connections can be crossed without a reload. */
data class KnownMap(
    val key: MapKey,
    val width: Int,
    val height: Int,
    val connections: List<KnownConnection>,
)

/** One GbaConnection: [offset] in tiles along the shared edge, as in the decomp's map.json. */
data class KnownConnection(
    val direction: de.fiereu.openmmo.common.enums.Direction,
    val offset: Int,
    val target: MapKey,
)

/** The text box the server opened, already split into the pages the box shows. */
data class DialogView(
    val seq: Int,
    val actionType: Int,
    val textId: Int,
    val entityId: Long,
    /** Each page is at most two lines, as the GBA box shows them. */
    val pages: List<List<String>>,
    val page: Int = 0,
    /** Choices shown once the last page is up; empty for a plain box. */
    val choices: List<String> = emptyList(),
    val cursor: Int = 0,
    /** Values sent back for each choice, parallel to [choices]. */
    val choiceValues: List<Int> = emptyList(),
) {
  val onLastPage: Boolean
    get() = page >= pages.size - 1
}

data class ShopOffer(val itemId: Int, val name: String, val price: Int)

data class ShopView(
    val npcEntityId: Long,
    val offers: List<ShopOffer>,
    val cursor: Int = 0,
    /** A line shown under the list, the result of the last buy or sell. */
    val status: String = "",
)

/**
 * The overworld UI state that is not the world itself: the open dialog, the open shop, the bag and
 * money, and whether a map transition is in progress. Written from the netty thread, snapshotted
 * for the renderer.
 */
class OverworldState {
  data class View(
      val dialog: DialogView?,
      val shop: ShopView?,
      val money: Int?,
      val bag: Map<Int, Int>,
      val transitioning: Boolean,
      val scriptLocked: Boolean,
      /** A server script owns the player: from its first box until the dialog is closed. */
      val inScript: Boolean,
      val knownMaps: Map<MapKey, KnownMap>,
      /** The last server notice (an item used, a refusal), numbered so a repeat still shows. */
      val notice: Notice? = null,
  )

  /** [kind] is a [de.fiereu.openmmo.client.net.NoticeKind] wire name (presence, reward, …). */
  data class Notice(val seq: Int, val text: String, val kind: String = "info")

  private val lock = Any()
  private var dialog: DialogView? = null
  private var shop: ShopView? = null
  private var money: Int? = null
  private val bag = sortedMapOf<Int, Int>()
  private var transitioning = false
  private var scriptLocked = false
  private var inScript = false
  private val knownMaps = mutableMapOf<MapKey, KnownMap>()
  private var notice: Notice? = null

  fun view(): View =
      synchronized(lock) {
        View(
            dialog,
            shop,
            money,
            bag.toMap(),
            transitioning,
            scriptLocked,
            inScript,
            knownMaps.toMap(),
            notice)
      }

  fun rememberMap(map: KnownMap) = synchronized(lock) { knownMaps[map.key] = map }

  fun knownMap(key: MapKey): KnownMap? = synchronized(lock) { knownMaps[key] }

  fun setDialog(value: DialogView?) = synchronized(lock) { dialog = value }

  fun updateDialog(f: (DialogView) -> DialogView?) = synchronized(lock) { dialog = dialog?.let(f) }

  fun setShop(value: ShopView?) = synchronized(lock) { shop = value }

  fun updateShop(f: (ShopView) -> ShopView?) = synchronized(lock) { shop = shop?.let(f) }

  fun setMoney(value: Int) = synchronized(lock) { money = value }

  fun setBag(items: Map<Int, Int>) =
      synchronized(lock) {
        bag.clear()
        items.filterValues { it > 0 }.forEach { (k, v) -> bag[k] = v }
      }

  fun setStack(itemId: Int, quantity: Int) =
      synchronized(lock) { if (quantity > 0) bag[itemId] = quantity else bag.remove(itemId) }

  fun setNotice(text: String, kind: String = "info") =
      synchronized(lock) { notice = Notice((notice?.seq ?: 0) + 1, text, kind) }

  fun setTransitioning(value: Boolean) = synchronized(lock) { transitioning = value }

  fun setInScript(value: Boolean) = synchronized(lock) { inScript = value }

  fun setScriptLocked(value: Boolean) = synchronized(lock) { scriptLocked = value }
}
