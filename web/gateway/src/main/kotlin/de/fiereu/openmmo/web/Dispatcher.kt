package de.fiereu.openmmo.web

import de.fiereu.openmmo.client.net.MarketSortOrder
import de.fiereu.openmmo.common.enums.Direction
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
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

  /** Use bag [item] in the field, on party monster [mon] (its id), or on nothing. */
  fun itemUse(item: Int, mon: Long?): String?

  fun partyMove(from: Int, to: Int): String?

  fun pcDeposit(slot: Int): String?

  fun pcWithdraw(slot: Int): String?

  fun battleFight(move: Int): String?

  /** r3-battle: a move for battle position [pos] aimed at [targetSide] (me/foe) [targetPos]. */
  fun battleFightAt(move: Int, pos: Int?, targetSide: String?, targetPos: Int?): String? =
      battleFight(move)

  /** r3-battle: a bag item in battle, on party monster [mon] (id) and move slot [moveSlot]. */
  fun battleItem(item: Int, mon: Long?, moveSlot: Int?, pos: Int?): String? = "not supported"

  /** r3-battle: throws the given ball item. */
  fun battleBallItem(item: Int): String? = battleBall()

  /** r3-battle: answers the evolution scene (false = B, stop it). */
  fun evolutionAnswer(proceed: Boolean): String? = "not supported"

  /** r3-battle: closes a finished evolution scene. */
  fun evolutionClose(): String? = null

  /** r3-battle: a challenge with rules including the 2v2 double option. */
  fun duelRulesDoubles(
      name: String,
      levelCap: Int,
      maxParty: Int,
      turnSeconds: Int,
      doubles: Boolean
  ): String? = duelRules(name, levelCap, maxParty, turnSeconds)

  fun battleSwitch(slot: Int): String?

  fun battleRun(): String?

  fun battleBall(): String?

  fun battleLearn(slot: Int?): String?

  fun battleClose(): String?

  fun duel(name: String): String?

  /** A challenge with rules: [levelCap] 0 = none, [maxParty] 1-6, [turnSeconds] 0 = no timer. */
  fun duelRules(name: String, levelCap: Int, maxParty: Int, turnSeconds: Int): String?

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
  private const val MAX_PC_SLOT = 14 * 30 - 1
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
        "item.use" -> {
          val mon = m["mon"]
          g.itemUse(m.reqInt("item"), if (mon == null || mon is JsonNull) null else m.id("mon"))
        }
        "party.move" -> g.partyMove(m.reqInt("from", 0..5), m.reqInt("to", 0..5))
        "pc.deposit" -> g.pcDeposit(m.reqInt("slot", 0..5))
        "pc.withdraw" -> g.pcWithdraw(m.reqInt("slot", 0..MAX_PC_SLOT))
        "battle.fight" ->
            if (m.keys.none { it in setOf("pos", "target") }) g.battleFight(m.reqInt("move", 0..3))
            else {
              val target = (m["target"] as? JsonObject)
              val side = target?.get("side")?.let { (it as? JsonPrimitive)?.contentOrNull }
              if (side != null && side !in setOf("me", "foe")) throw bad("target")
              val tpos = target?.get("pos")?.let { (it as? JsonPrimitive)?.intOrNull }
              if (tpos != null && tpos !in 0..1) throw bad("target")
              g.battleFightAt(
                  m.reqInt("move", 0..3),
                  m.int("pos")?.also { if (it !in 0..1) throw bad("pos") },
                  side,
                  tpos)
            }
        "battle.item" -> {
          val mon = m["mon"]
          g.battleItem(
              m.reqInt("item"),
              if (mon == null || mon is JsonNull) null else m.id("mon"),
              m.int("moveSlot")?.also { if (it !in 0..3) throw bad("moveSlot") },
              m.int("pos")?.also { if (it !in 0..1) throw bad("pos") })
        }
        "evolution.answer" -> g.evolutionAnswer(m.bool("proceed") ?: throw bad("proceed"))
        "evolution.close" -> g.evolutionClose()
        "battle.switch" -> g.battleSwitch(m.reqInt("slot", 0..5))
        "battle.run" -> g.battleRun()
        "battle.ball" ->
            m.int("item").let { if (it != null) g.battleBallItem(it) else g.battleBall() }
        "battle.learn" -> {
          val slot = m["slot"]
          if (slot == null || slot is JsonNull) g.battleLearn(null)
          else g.battleLearn(m.reqInt("slot", 0..3))
        }
        "battle.close" -> g.battleClose()
        "duel" ->
            if (m.keys.none { it in DUEL_RULE_FIELDS }) g.duel(m.name("name"))
            else
                (m.bool("doubles") ?: false).let { doubles ->
                  val cap =
                      m.int("levelCap")?.also {
                        if (it != 0 && it !in 1..100) throw bad("levelCap")
                      } ?: 0
                  val party = m.int("party")?.also { if (it !in 1..6) throw bad("party") } ?: 6
                  val timer =
                      m.int("timer")?.also { if (it != 0 && it !in 5..240) throw bad("timer") } ?: 0
                  if (doubles) g.duelRulesDoubles(m.name("name"), cap, party, timer, true)
                  else g.duelRules(m.name("name"), cap, party, timer)
                }
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

  private val DUEL_RULE_FIELDS = setOf("levelCap", "party", "timer", "doubles")

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
