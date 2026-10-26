package de.fiereu.openmmo.web

import de.fiereu.openmmo.client.net.MarketSortOrder
import de.fiereu.openmmo.common.enums.Direction
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * What a logged-in browser can make its game client do. [GameSession] implements it over the real
 * clients; tests record the calls. A non-null return is an error to report to the browser.
 */
interface GameActions {
  fun step(dir: Direction, run: Boolean, seq: Int): String?

  fun face(dir: Direction): String?

  fun say(text: String): String?

  fun a(): String?

  fun b(): String?

  fun cursor(delta: Int): String?

  fun choose(index: Int): String?

  fun shopBuy(item: Int, qty: Int): String?

  fun shopSell(item: Int, qty: Int): String?

  fun shopClose(): String?

  fun battleFight(move: Int): String?

  fun battleSwitch(slot: Int): String?

  fun battleRun(): String?

  fun battleBall(): String?

  fun battleLearn(slot: Int?): String?

  fun battleClose(): String?

  fun duel(name: String): String?

  fun duelAnswer(accept: Boolean): String?

  fun tradeRequest(name: String): String?

  fun tradeAction(action: String): String?

  fun tradeMon(slot: Int): String?

  fun tradeItem(item: Int, qty: Int): String?

  fun tradeMoney(amount: Int): String?

  fun marketSearch(
      pokemon: Boolean,
      id: Int?,
      min: Int?,
      max: Int?,
      sort: MarketSortOrder,
      page: Int
  ): String?

  fun marketMine(): String?

  fun marketInventory(): String?

  fun marketListMon(slot: Int, price: Int): String?

  fun marketListItem(item: Int, qty: Int, price: Int): String?

  fun marketBuy(id: Long): String?

  fun marketCancel(id: Long): String?
}

/** A field that is missing or of the wrong type. */
class BadMessage(msg: String) : RuntimeException(msg)

/** Routes one browser message (already parsed, not `login`/`ping`) to [GameActions]. */
object Dispatcher {
  const val MAX_TEXT = 256
  private const val MAX_QTY = 999
  private val TRADE_ACTIONS = setOf("accept", "decline", "ready", "confirm", "cancel")

  /** Returns an error message, or null when the action was taken. */
  fun dispatch(t: String, m: JsonObject, g: GameActions): String? =
      try {
        route(t, m, g)
      } catch (e: BadMessage) {
        e.message
      }

  private fun route(t: String, m: JsonObject, g: GameActions): String? =
      when (t) {
        "step" -> g.step(m.dir("dir"), m.bool("run") ?: false, m.int("seq") ?: throw bad("seq"))
        "face" -> g.face(m.dir("dir"))
        "say" -> {
          val text = m.string("text").trim()
          if (text.isEmpty()) "empty text"
          else if (text.length > MAX_TEXT) "text too long" else g.say(text)
        }
        "a" -> g.a()
        "b" -> g.b()
        "cursor" -> g.cursor((m.int("delta") ?: throw bad("delta")).coerceIn(-1, 1))
        "choose" -> g.choose(m.reqInt("index"))
        "shop.buy" -> g.shopBuy(m.reqInt("item"), m.qty("qty"))
        "shop.sell" -> g.shopSell(m.reqInt("item"), m.qty("qty"))
        "shop.close" -> g.shopClose()
        "battle.fight" -> g.battleFight(m.reqInt("move", 0..3))
        "battle.switch" -> g.battleSwitch(m.reqInt("slot", 0..5))
        "battle.run" -> g.battleRun()
        "battle.ball" -> g.battleBall()
        "battle.learn" -> {
          val slot = m["slot"]
          if (slot == null || slot is JsonNull) g.battleLearn(null)
          else g.battleLearn(m.reqInt("slot", 0..3))
        }
        "battle.close" -> g.battleClose()
        "duel" -> g.duel(m.name("name"))
        "duel.answer" -> g.duelAnswer(m.bool("accept") ?: throw bad("accept"))
        "trade.request" -> g.tradeRequest(m.name("name"))
        "trade.accept",
        "trade.decline",
        "trade.ready",
        "trade.confirm",
        "trade.cancel" ->
            g.tradeAction(t.removePrefix("trade.").also { check(it in TRADE_ACTIONS) })
        "trade.mon" -> g.tradeMon(m.reqInt("slot", 0..5))
        "trade.item" -> g.tradeItem(m.reqInt("item"), m.reqInt("qty", 0..MAX_QTY))
        "trade.money" -> g.tradeMoney(m.reqInt("amount", 0..Int.MAX_VALUE))
        "market.search" -> {
          val pokemon =
              when (val k = m.optString("kind")) {
                null,
                "mon" -> true
                "item" -> false
                else -> throw BadMessage("bad kind: $k")
              }
          val sort =
              when (val s = m.optString("sort")) {
                null,
                "price" -> MarketSortOrder.PRICE_ASC
                "price-desc" -> MarketSortOrder.PRICE_DESC
                "new" -> MarketSortOrder.NEWEST
                else -> throw BadMessage("bad sort: $s")
              }
          g.marketSearch(
              pokemon,
              m.int("id"),
              m.int("min"),
              m.int("max"),
              sort,
              (m.int("page") ?: 0).coerceIn(0, Short.MAX_VALUE.toInt()))
        }
        "market.mine" -> g.marketMine()
        "market.inventory" -> g.marketInventory()
        "market.listMon" -> g.marketListMon(m.reqInt("slot", 0..5), m.price())
        "market.listItem" -> g.marketListItem(m.reqInt("item"), m.qty("qty"), m.price())
        "market.buy" -> g.marketBuy(m.id("id"))
        "market.cancel" -> g.marketCancel(m.id("id"))
        else -> "unknown message type: $t"
      }

  private fun bad(field: String) = BadMessage("missing or bad field: $field")

  private fun JsonObject.prim(k: String): JsonPrimitive? =
      (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }

  fun JsonObject.int(k: String): Int? {
    val p = prim(k) ?: return null
    if (p.isString) throw bad(k)
    return p.intOrNull ?: throw bad(k)
  }

  private fun JsonObject.reqInt(k: String, range: IntRange? = null): Int {
    val v = int(k) ?: throw bad(k)
    if (range != null && v !in range) throw BadMessage("$k out of range")
    return v
  }

  private fun JsonObject.qty(k: String) = reqInt(k, 1..MAX_QTY)

  private fun JsonObject.price() = reqInt("price", 1..Int.MAX_VALUE)

  private fun JsonObject.bool(k: String): Boolean? {
    val p = prim(k) ?: return null
    if (p.isString) throw bad(k)
    return p.booleanOrNull ?: throw bad(k)
  }

  private fun JsonObject.optString(k: String): String? {
    val p = prim(k) ?: return null
    if (!p.isString) throw bad(k)
    return p.content
  }

  private fun JsonObject.string(k: String): String = optString(k) ?: throw bad(k)

  private fun JsonObject.name(k: String): String {
    val s = string(k).trim()
    if (s.isEmpty() || s.length > 32) throw bad(k)
    return s
  }

  private fun JsonObject.dir(k: String): Direction =
      StateJson.parseDir(optString(k)) ?: throw bad(k)

  /** Listing ids are 64-bit; the browser sends them as strings, numbers are accepted too. */
  private fun JsonObject.id(k: String): Long {
    val p = prim(k) ?: throw bad(k)
    return (if (p.isString) p.content.toLongOrNull() else p.longOrNull) ?: throw bad(k)
  }
}
