package de.fiereu.openmmo.web

import de.fiereu.openmmo.client.net.BattleFx
import de.fiereu.openmmo.client.net.BattleMon
import de.fiereu.openmmo.client.net.BattleNames
import de.fiereu.openmmo.client.net.BattleState
import de.fiereu.openmmo.client.net.MarketClient
import de.fiereu.openmmo.client.net.MarketEntry
import de.fiereu.openmmo.client.net.SocialClient
import de.fiereu.openmmo.client.world.Entity
import de.fiereu.openmmo.client.world.MapKey
import de.fiereu.openmmo.client.world.OverworldState
import de.fiereu.openmmo.client.world.WorldState
import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.net.game.packets.battle.DuelRules
import de.fiereu.openmmo.net.game.packets.trade.TradeOfferView
import de.fiereu.openmmo.net.game.packets.trade.TradePhase
import de.fiereu.openmmo.net.game.packets.trade.TradeStage
import de.fiereu.openmmo.net.game.packets.trade.TradeStatePacket
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** One read of every client snapshot, taken together so a state message is consistent. */
data class Sources(
    val world: WorldState.Snapshot,
    val battle: BattleState,
    val trade: TradeStatePacket,
    val party: List<Pokemon>,
    val pc: List<Pokemon> = emptyList(),
    /** The bag as the trade client saw it, used when the overworld has none yet. */
    val tradeBag: Map<Int, Int>,
    val tradeMoney: Int?,
    val market: MarketClient.State,
    val ackSeq: Int,
    /** Entities that changed tile in the last step duration. */
    val moving: Set<Long>,
    /** r3-social: friends, profile, guild, invite, trade history. */
    val social: SocialClient.State = SocialClient.State(),
    /** When (epoch ms) a pending trade request lapses, or 0. */
    val tradeExpiresAt: Long = 0,
)

/** The `state` sections of PROTOCOL.md section 3, built from [Sources]. Pure, so it is testable. */
class StateJson(private val names: Names) {
  private val socialJson = SocialJson(names)

  /** Every section, keyed by its name. A section with nothing to show is [JsonNull]. */
  fun build(s: Sources): Map<String, JsonElement> =
      linkedMapOf(
          "world" to world(s),
          "chat" to chat(s.world),
          "ui" to ui(s),
          "party" to party(s.party),
          "pc" to pc(s),
          "battle" to battle(s.battle),
          "invite" to invite(s.battle),
          "trade" to trade(s.trade, s.tradeExpiresAt),
          "market" to market(s),
          "social" to socialJson.social(s.social),
      )

  fun world(s: Sources): JsonElement {
    val w = s.world
    return buildJsonObject {
      put("map", w.map?.let { mapJson(it, w.mapWidth, w.mapHeight) } ?: JsonNull)
      put("self", w.selfId?.toString())
      put("inWorld", w.inWorld)
      putJsonArray("entities") {
        w.entities.values
            .filter { it.visible }
            .sortedBy { it.id }
            .forEach { add(entity(it, it.id in s.moving)) }
      }
      put("ackSeq", s.ackSeq)
    }
  }

  private fun mapJson(key: MapKey, width: Int, height: Int) = buildJsonObject {
    put("region", key.regionId)
    put("bank", key.bankId)
    put("map", key.mapId)
    put("name", names.mapName(key))
    put("width", width)
    put("height", height)
  }

  fun entity(e: Entity, moving: Boolean) = buildJsonObject {
    put("id", e.id.toString())
    put("kind", if (e.isPlayer) "player" else "npc")
    put("name", e.name)
    put("gfx", if (e.isPlayer) JsonNull else JsonPrimitive(e.graphicsId))
    put("x", e.x)
    put("y", e.y)
    put("facing", dir(e.facing))
    put("moving", moving)
    putJsonObject("map") {
      put("region", e.map.regionId)
      put("bank", e.map.bankId)
      put("map", e.map.mapId)
    }
  }

  fun chat(w: WorldState.Snapshot): JsonElement = buildJsonArray {
    w.chat.takeLast(CHAT_LINES).forEach {
      addJsonObject {
        put("sender", it.sender)
        put("text", it.message)
        // r3-social: local|global|trade|guild|whisper|system, "" for server notices.
        if (it.channel.isNotEmpty()) put("channel", it.channel.lowercase())
        if (it.to.isNotEmpty()) put("to", it.to)
      }
    }
  }

  fun ui(s: Sources): JsonElement {
    val v: OverworldState.View? = s.world.overworld
    return buildJsonObject {
      val d = v?.dialog
      put(
          "dialog",
          if (d == null) JsonNull
          else
              buildJsonObject {
                put("speaker", speakerOf(s.world, d.entityId))
                putJsonArray("lines") { d.pages.getOrNull(d.page).orEmpty().forEach { add(it) } }
                put("lastPage", d.onLastPage)
                putJsonArray("choices") { if (d.onLastPage) d.choices.forEach { add(it) } }
                put("cursor", d.cursor)
              })
      val shop = v?.shop
      put(
          "shop",
          if (shop == null) JsonNull
          else
              buildJsonObject {
                putJsonArray("offers") {
                  shop.offers.forEach {
                    addJsonObject {
                      put("item", it.itemId)
                      put("name", it.name)
                      put("price", it.price)
                      put("desc", names.itemInfo(it.itemId)?.description.orEmpty())
                    }
                  }
                }
                // What the clerk buys back: the bag, at half the item's price (never for 0).
                val bag = v.bag.takeIf { it.isNotEmpty() } ?: s.tradeBag
                putJsonArray("sell") {
                  bag.toSortedMap().forEach { (id, qty) ->
                    val price = (names.itemInfo(id)?.price ?: 0) / 2
                    if (qty > 0 && price > 0)
                        addJsonObject {
                          put("item", id)
                          put("name", names.item(id))
                          put("qty", qty)
                          put("price", price)
                        }
                  }
                }
                put("cursor", shop.cursor)
                put("status", shop.status)
              })
      put("money", v?.money ?: s.tradeMoney ?: s.market.money)
      val bag = v?.bag?.takeIf { it.isNotEmpty() } ?: s.tradeBag
      put("bag", bagJson(bag))
      put("locked", v != null && (v.transitioning || v.scriptLocked || v.inScript))
      val n = v?.notice
      put(
          "notice",
          if (n == null) JsonNull
          else
              buildJsonObject {
                put("seq", n.seq)
                put("text", n.text)
              })
    }
  }

  /** Who is talking: a named character's npc, or nobody (signs, generic npcs). */
  private fun speakerOf(w: WorldState.Snapshot, entityId: Long): String? {
    if (entityId <= 0) return null
    val e = w.entities[entityId] ?: return null
    if (e.isPlayer) return null
    return names.speaker(e.map.regionId, e.graphicsId)
  }

  fun bagJson(bag: Map<Int, Int>): JsonArray = buildJsonArray {
    bag.toSortedMap().forEach { (id, qty) ->
      if (qty > 0)
          addJsonObject {
            put("item", id)
            put("name", names.item(id))
            put("qty", qty)
            val info = names.itemInfo(id)
            if (info != null) {
              put("pocket", info.pocket)
              put("desc", info.description)
              put("use", info.use)
            }
          }
    }
  }

  fun party(party: List<Pokemon>): JsonElement = buildJsonArray {
    party
        .sortedBy { it.containerSlot }
        .forEachIndexed { i, mon ->
          addJsonObject {
            put("slot", i)
            put("dex", mon.dexId)
            val species = names.species(mon.dexId)
            put("name", species)
            put(
                "nick",
                mon.nickname.takeIf { it.isNotEmpty() && !it.equals(species, ignoreCase = true) })
            put("level", mon.level.toInt())
            put("hp", mon.hp.toInt())
            put("maxHp", names.maxHp(mon))
            put("id", mon.id.toString())
            names.summary(mon)?.let { sum ->
              putJsonObject("stats") { sum.stats.forEach { (k, v) -> put(k, v) } }
              put("nature", sum.nature)
              put("ability", sum.ability)
              putJsonArray("types") { sum.types.forEach { add(it) } }
            }
            putJsonArray("moves") {
              mon.moves
                  .filter { it.id.toInt() != 0 }
                  .forEach {
                    addJsonObject {
                      put("name", names.move(it.id.toInt()))
                      put("pp", it.pp.toInt())
                      put("maxPp", names.maxPp(it.id.toInt()))
                    }
                  }
            }
            put("ot", mon.ot)
            put("xp", mon.xp)
            put("shiny", mon.isShiny)
          }
        }
  }

  /** The PC boxes, and whether the player stands where the PC can be used. */
  fun pc(s: Sources): JsonElement = buildJsonObject {
    put("here", s.world.map?.let(names::hasPc) ?: false)
    putJsonArray("mons") {
      s.pc
          .sortedBy { it.containerSlot }
          .forEach { mon ->
            addJsonObject {
              put("slot", mon.containerSlot.toInt())
              put("id", mon.id.toString())
              put("dex", mon.dexId)
              put("name", names.species(mon.dexId))
              put("level", mon.level.toInt())
              put("hp", mon.hp.toInt())
              put("maxHp", names.maxHp(mon))
            }
          }
    }
  }

  fun battle(b: BattleState): JsonElement {
    if (!b.active) return JsonNull
    return buildJsonObject {
      put("wild", b.wild)
      put("opponentName", b.opponentName)
      put("phase", b.phase.name)
      put("turn", b.turn)
      put("activeSlot", b.activeSlot)
      put("me", b.me?.let { monJson(it, withMoves = true) } ?: JsonNull)
      put("foe", b.foe?.takeIf { it.revealed }?.let { monJson(it, withMoves = false) } ?: JsonNull)
      putJsonArray("party") { b.party.forEach { add(monJson(it, withMoves = false)) } }
      putJsonArray("log") { b.log.takeLast(BATTLE_LOG_LINES).forEach { add(it) } }
      put("result", b.result)
      put("rules", b.rules?.let(::rulesJson) ?: JsonNull)
      put(
          "timer",
          b.timer?.let { t ->
            buildJsonObject {
              put("id", t.id)
              put("seconds", t.seconds)
              put("mustAct", t.mustAct)
              put("timeouts", t.timeouts)
              put("rivalTimeouts", t.rivalTimeouts)
              put("autoPicked", t.autoPicked)
              put("maxTimeouts", DuelRules.MAX_TIMEOUTS)
            }
          } ?: JsonNull)
      putJsonArray("fx") { b.fx.takeLast(BATTLE_FX).forEach { add(fxJson(it)) } }
      val learn = b.pendingLearn
      put(
          "learn",
          if (learn == null) JsonNull
          else
              buildJsonObject {
                put("move", learn.second.firstOrNull()?.let(names::move))
                val mon = b.party.firstOrNull { it.entityId == learn.first }
                putJsonArray("current") { mon?.moves?.forEach { add(names.move(it.id)) } }
                put("mon", mon?.let { names.species(it.species) })
                learn.second.firstOrNull()?.let { id -> put("offer", moveInfo(id, null, null)) }
                putJsonArray("moves") {
                  mon?.moves?.forEach { add(moveInfo(it.id, it.pp, it.maxPp)) }
                }
              })
    }
  }

  private fun monJson(m: BattleMon, withMoves: Boolean) = buildJsonObject {
    put("dex", m.species)
    put("name", names.species(m.species))
    put("level", m.level)
    put("hp", m.hp.coerceAtLeast(0))
    put("maxHp", m.maxHp)
    if (withMoves) {
      BattleNames.expFraction(m.species, m.level, m.xp)?.let {
        put("exp", Math.round(it * 1000) / 1000.0)
      }
      putJsonArray("moves") {
        m.moves.forEach {
          addJsonObject {
            put("name", names.move(it.id))
            put("pp", it.pp)
            put("maxPp", it.maxPp)
            put("type", BattleNames.moveType(it.id))
            put("power", BattleNames.power(it.id))
          }
        }
      }
    }
  }

  fun invite(b: BattleState): JsonElement =
      b.pendingInviteFrom?.let {
        buildJsonObject {
          put("from", it)
          put("rules", b.pendingInviteRules?.let(::rulesJson) ?: JsonNull)
        }
      } ?: JsonNull

  private fun rulesJson(r: DuelRules) = buildJsonObject {
    put("levelCap", r.levelCap)
    put("party", r.maxParty)
    put("timer", r.turnSeconds)
  }

  private fun moveInfo(id: Int, pp: Int?, maxPp: Int?) = buildJsonObject {
    put("name", names.move(id))
    put("type", BattleNames.moveType(id))
    put("power", BattleNames.power(id))
    put("accuracy", BattleNames.accuracy(id))
    put("pp", pp ?: names.maxPp(id))
    put("maxPp", maxPp ?: names.maxPp(id))
  }

  private fun fxJson(f: BattleFx): JsonElement = buildJsonObject {
    put("n", f.seq)
    put("k", f.kind)
    f.side?.let { put("side", it) }
    for ((k, v) in f.data) put(k, anyJson(v))
  }

  private fun anyJson(v: Any?): JsonElement =
      when (v) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        is Map<*, *> -> buildJsonObject { for ((k, x) in v) put(k.toString(), anyJson(x)) }
        is List<*> -> buildJsonArray { v.forEach { add(anyJson(it)) } }
        else -> JsonPrimitive(v.toString())
      }

  /** A closed trade stays visible once with its reason; a closed trade with no reason is null. */
  fun trade(t: TradeStatePacket, expiresAt: Long = 0): JsonElement {
    if (t.phase == TradePhase.CLOSED && t.message.isEmpty()) return JsonNull
    return buildJsonObject {
      put("phase", t.phase.name)
      put("partner", t.partnerName)
      put("message", t.message)
      if (expiresAt > 0) put("expiresAt", expiresAt)
      put("mine", offer(t.mine, t.myStage))
      put("theirs", offer(t.theirs, t.theirStage))
    }
  }

  private fun offer(o: TradeOfferView, stage: TradeStage) = buildJsonObject {
    put("stage", stage.name)
    putJsonArray("mons") {
      o.mons.forEach {
        addJsonObject {
          put("slot", it.partySlot)
          put("dex", it.dexId)
          put("name", names.species(it.dexId))
          put("level", it.level)
        }
      }
    }
    putJsonArray("items") {
      o.items.forEach {
        addJsonObject {
          put("item", it.itemId)
          put("name", names.item(it.itemId))
          put("qty", it.quantity)
        }
      }
    }
    put("money", o.money)
    // r3-social: monsters offered from the PC, `slot` is the PC slot.
    putJsonArray("pcMons") {
      o.pcMons.forEach {
        addJsonObject {
          put("slot", it.partySlot)
          put("dex", it.dexId)
          put("name", names.species(it.dexId))
          put("level", it.level)
        }
      }
    }
  }

  fun market(s: Sources): JsonElement {
    val m = s.market
    val mineIds = m.mine.map { it.listingId }.toSet()
    val selfId = s.world.selfId
    return buildJsonObject {
      // The overworld's balance follows every money packet (trade, shop, market); the market's
      // own copy only follows market replies, so prefer the former.
      put("money", s.world.overworld?.money ?: m.money)
      putJsonArray("browse") {
        m.browse.forEach {
          add(entry(it, it.listingId in mineIds || it.sellerId == selfId, m.sellers[it.listingId]))
        }
      }
      put("browseTotal", m.browseTotal)
      put("page", m.browsePage)
      putJsonArray("mine") { m.mine.forEach { add(entry(it, true, m.sellers[it.listingId])) } }
      putJsonObject("inventory") {
        put("party", party(m.party))
        put("bag", bagJson(m.items.associate { it.itemId.toInt() to it.quantity }))
      }
      putJsonArray("log") { m.log.forEach { add(it) } }
      // r3-social extras.
      put("info", m.info?.let(socialJson::info) ?: JsonNull)
      put("history", m.priceHistory?.let(socialJson::priceHistory) ?: JsonNull)
      put("sales", m.sales?.let(socialJson::sales) ?: JsonNull)
      put("openSeq", m.openSeq)
    }
  }

  private fun entry(e: MarketEntry, mine: Boolean, seller: String? = null) = buildJsonObject {
    put("id", e.listingId.toString())
    put("kind", if (e.isPokemon) "mon" else "item")
    put("dex", if (e.isPokemon) JsonPrimitive(e.refId) else JsonNull)
    put("item", if (e.isPokemon) JsonNull else JsonPrimitive(e.refId))
    put("name", if (e.isPokemon) e.nickname ?: names.species(e.refId) else names.item(e.refId))
    put("level", e.level)
    put("qty", e.quantity)
    put("price", e.price)
    put("expiresAt", e.expiresAtEpoch)
    put("mine", mine)
    put("seller", seller?.takeIf { it.isNotEmpty() })
  }

  companion object {
    const val CHAT_LINES = 50
    const val BATTLE_LOG_LINES = 50
    const val BATTLE_FX = 60

    fun dir(d: Direction): String =
        when (d) {
          Direction.UP -> "up"
          Direction.DOWN -> "down"
          Direction.LEFT -> "left"
          Direction.RIGHT -> "right"
          else -> d.name.lowercase()
        }

    fun parseDir(s: String?): Direction? =
        when (s) {
          "up" -> Direction.UP
          "down" -> Direction.DOWN
          "left" -> Direction.LEFT
          "right" -> Direction.RIGHT
          else -> null
        }
  }
}

/**
 * Per-section change detection: remembers the last JSON sent for each section and returns only the
 * ones that differ. The first call returns all of them.
 */
class SectionDiffer {
  private val last = HashMap<String, JsonElement>()

  fun diff(sections: Map<String, JsonElement>): Map<String, JsonElement> {
    val out = linkedMapOf<String, JsonElement>()
    for ((k, v) in sections) {
      if (!last.containsKey(k) || last[k] != v) {
        out[k] = v
        last[k] = v
      }
    }
    return out
  }

  fun reset() = last.clear()
}

/** Marks entities `moving` for one step duration after their tile changes. */
class MovementTracker(private val windowMs: Long = 300) {
  private val lastPos = HashMap<Long, Long>()
  private val movedAt = HashMap<Long, Long>()

  fun update(entities: Collection<Entity>, nowMs: Long): Set<Long> {
    val seen = HashSet<Long>()
    for (e in entities) {
      seen += e.id
      val pos = (e.x.toLong() shl 32) or (e.y.toLong() and 0xFFFFFFFFL)
      val prev = lastPos.put(e.id, pos)
      if (prev != null && prev != pos) movedAt[e.id] = nowMs
    }
    lastPos.keys.retainAll(seen)
    movedAt.keys.retainAll(seen)
    return movedAt.filterValues { nowMs - it < windowMs }.keys
  }

  fun anyMoving(nowMs: Long): Boolean = movedAt.values.any { nowMs - it < windowMs }
}
