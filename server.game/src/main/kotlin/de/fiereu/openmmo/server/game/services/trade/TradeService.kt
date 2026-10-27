package de.fiereu.openmmo.server.game.services.trade

import de.fiereu.network.PacketEvent
import de.fiereu.network.SessionContext
import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.net.game.packets.GbaEntityMovePacket
import de.fiereu.openmmo.net.game.packets.TradeActionPacket
import de.fiereu.openmmo.net.game.packets.TradeSelectMonPacket
import de.fiereu.openmmo.net.game.packets.social.TradeOfferPcMonPacket
import de.fiereu.openmmo.net.game.packets.trade.TradeAction
import de.fiereu.openmmo.net.game.packets.trade.TradeItemView
import de.fiereu.openmmo.net.game.packets.trade.TradeMonView
import de.fiereu.openmmo.net.game.packets.trade.TradeOfferItemPacket
import de.fiereu.openmmo.net.game.packets.trade.TradeOfferKind
import de.fiereu.openmmo.net.game.packets.trade.TradeOfferView
import de.fiereu.openmmo.net.game.packets.trade.TradePhase
import de.fiereu.openmmo.net.game.packets.trade.TradeRequestPacket
import de.fiereu.openmmo.net.game.packets.trade.TradeStage
import de.fiereu.openmmo.net.game.packets.trade.TradeStatePacket
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import de.fiereu.openmmo.server.game.services.BattleService
import de.fiereu.openmmo.server.game.services.WorldStateService
import de.fiereu.openmmo.server.game.services.notice
import de.fiereu.openmmo.server.game.services.social.BlockList
import de.fiereu.openmmo.server.game.services.social.RateKind
import de.fiereu.openmmo.server.game.services.social.RateLimiter
import de.fiereu.openmmo.server.game.services.social.SocialConfig
import de.fiereu.openmmo.server.game.services.social.TradeProximity
import de.fiereu.openmmo.server.game.session.PLAYER_STATE
import de.fiereu.openmmo.server.game.session.SessionRegistry
import de.fiereu.openmmo.server.game.storage.CharacterStore
import de.fiereu.openmmo.server.game.storage.InMemorySocialRepository
import de.fiereu.openmmo.server.game.storage.SocialRepository
import de.fiereu.openmmo.server.game.storage.StoredCharacter
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val log = KotlinLogging.logger {}

/** One trade between [initiator] and [target], from the request until it closes. */
internal class Trade(
    val initiator: Long,
    val target: Long,
    val requestedAtMs: Long = System.currentTimeMillis(),
) {
  var open = false
  /** Set while the swap is being written. Nothing may change the trade until it finishes. */
  var committing = false
  /** Who left while [committing]; the trade is closed for them once the write finishes. */
  var abandonedBy: Long? = null
  val offers = mutableMapOf(initiator to TradeOffer(), target to TradeOffer())
  val stages = mutableMapOf(initiator to TradeStage.NONE, target to TradeStage.NONE)

  fun partnerOf(charId: Long): Long = if (charId == initiator) target else initiator

  fun resetStages() {
    stages.keys.forEach { stages[it] = TradeStage.NONE }
  }
}

/**
 * Player to player trading: a request the other player accepts, a window where both put up to six
 * party monsters, bag items and money, and a two step confirmation. Any change to either offer
 * drops both sides back to not ready. When both confirmed, [CharacterStore.commitPair] swaps the
 * two characters in one database transaction.
 *
 * Trade bookkeeping is guarded by one lock. The write happens outside it with the trade marked
 * [Trade.committing], so a slow database does not stall unrelated trades.
 */
@Singleton
class TradeService
@Inject
constructor(
    private val characterStore: CharacterStore,
    private val sessions: SessionRegistry,
    private val battleService: BattleService,
    private val worldState: WorldStateService,
    private val items: ItemRegistry,
    private val config: SocialConfig = SocialConfig(),
    private val limiter: RateLimiter = RateLimiter(config.rates),
    private val history: SocialRepository = InMemorySocialRepository(),
    private val blocks: BlockList = BlockList(history),
    private val evolution: TradeEvolutionHook = TradeEvolutionHook.NONE,
    private val species: SpeciesRegistry = SpeciesRegistry(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
  private val lock = Any()
  private val trades = HashMap<Long, Trade>()

  suspend fun onRequest(event: PacketEvent<TradeRequestPacket>) {
    val me = event.session.charId() ?: return
    val myself = characterStore.getCharacter(me) ?: return
    val userId = event.session.attributes[PLAYER_STATE]?.userId ?: return
    if (!limiter.tryAcquire(userId, RateKind.TRADE_REQUEST)) {
      return refuse(event.session, "You are sending trade requests too fast.")
    }
    val wanted = event.packet.targetName.trim()
    val other =
        sessions.onlineCharacterIds().firstNotNullOfOrNull { id ->
          characterStore.getCharacter(id)?.takeIf { it.info.name.equals(wanted, ignoreCase = true) }
        }
    if (other == null) return refuse(event.session, "$wanted is not online.")
    val them = other.info.id
    busyReason(me, "You")?.let {
      return refuse(event.session, it)
    }
    busyReason(them, other.info.name)?.let {
      return refuse(event.session, it)
    }
    if (them == me) return refuse(event.session, "You cannot trade with yourself.")
    if (blocks.isBlocked(them, me)) {
      return refuse(event.session, "${other.info.name} is not accepting trade requests.")
    }
    if (blocks.isBlocked(me, them)) {
      return refuse(event.session, "Unblock ${other.info.name} to trade with them.")
    }
    proximityReason(myself, other)?.let {
      return refuse(event.session, it)
    }
    val trade = Trade(me, them)
    synchronized(lock) {
      if (me in trades) return refuse(event.session, "You are already trading.")
      if (them in trades) return refuse(event.session, "${other.info.name} is already trading.")
      trades[me] = trade
      trades[them] = trade
    }
    log.info { "char=$me asked char=$them to trade" }
    sessions.getByCharacterId(them)?.send(notice("${myself.info.name} wants to trade with you."))
    broadcast(trade)
    scope.launch {
      delay(config.tradeRequestSeconds.seconds)
      val lapsed = synchronized(lock) { trades[me] === trade && !trade.open }
      if (lapsed) close(trade, "The trade request expired.")
    }
  }

  /** Null when [a] and [b] are close enough to trade under [SocialConfig.tradeProximity]. */
  internal fun proximityReason(a: StoredCharacter, b: StoredCharacter): String? {
    val rule = config.tradeProximity
    if (rule == TradeProximity.Anywhere) return null
    val sameMap =
        a.info.positionRegionId == b.info.positionRegionId &&
            a.info.positionBankId == b.info.positionBankId &&
            a.info.positionMapId == b.info.positionMapId
    if (!sameMap) return "${b.info.name} has to be on the same map to trade."
    if (rule is TradeProximity.Within) {
      val d =
          kotlin.math.abs(a.info.positionX - b.info.positionX) +
              kotlin.math.abs(a.info.positionY - b.info.positionY)
      if (d > rule.tiles) return "Stand within ${rule.tiles} steps of ${b.info.name} to trade."
    }
    return null
  }

  /** The window is open: the player stands still and cannot shop until it closes. */
  fun locksField(charId: Long): Boolean = synchronized(lock) { trades[charId]?.open == true }

  /**
   * Refuses a step while [locksField]: snaps the client back to the server's tile, the way a step
   * during a script is refused. True when the step was refused.
   */
  fun refuseStep(session: SessionContext): Boolean {
    val charId = session.charId() ?: return false
    if (!locksField(charId)) return false
    val info = characterStore.getCharacter(charId)?.info ?: return true
    session.send(
        GbaEntityMovePacket(
            entityId = charId,
            bankId = info.positionBankId.toInt() and 0xff,
            mapId = info.positionMapId.toInt() and 0xff,
            x = info.positionX.toInt(),
            y = info.positionY.toInt(),
            movementMode = 2,
            direction = session.attributes[PLAYER_STATE]?.facingDirection ?: Direction.DOWN,
        ))
    return true
  }

  /** Refuses a shop or npc action while [locksField], with a notice. True when refused. */
  fun refuseFieldAction(session: SessionContext): Boolean {
    val charId = session.charId() ?: return false
    if (!locksField(charId)) return false
    session.send(notice("Finish your trade first."))
    return true
  }

  /** Toggles the monster in PC slot [TradeOfferPcMonPacket.pcSlot] on or off the table. */
  fun onSelectPcMon(event: PacketEvent<TradeOfferPcMonPacket>) {
    val me = event.session.charId() ?: return
    val stored = characterStore.getCharacter(me) ?: return
    val mon =
        stored.pcStorage.firstOrNull { it.containerSlot.toInt() == event.packet.pcSlot }
            ?: return tell(me, "There is no monster in PC slot ${event.packet.pcSlot}.")
    editOffer(me) { offer ->
      if (mon.id in offer.monIds) {
        offer.copy(monIds = offer.monIds - mon.id)
      } else if (offer.monIds.size >= TradeSwap.MAX_OFFERED_MONS) {
        null
      } else {
        offer.copy(monIds = offer.monIds + mon.id)
      }
    }
  }

  suspend fun onAction(event: PacketEvent<TradeActionPacket>) {
    val me = event.session.charId() ?: return
    when (event.packet.action) {
      TradeAction.ACCEPT -> accept(me)
      TradeAction.DECLINE -> close(me, "declined the trade")
      TradeAction.CANCEL -> close(me, "cancelled the trade")
      TradeAction.READY -> ready(me)
      TradeAction.CONFIRM -> confirm(me)
      else -> log.warn { "char=$me sent unknown trade action ${event.packet.action}" }
    }
  }

  /** Toggles the monster in party slot [TradeSelectMonPacket.slotIndex] on or off the table. */
  fun onSelectMon(event: PacketEvent<TradeSelectMonPacket>) {
    val me = event.session.charId() ?: return
    val stored = characterStore.getCharacter(me) ?: return
    val mon =
        stored.pokemon.sortedBy { it.containerSlot }.getOrNull(event.packet.slotIndex)
            ?: return tell(me, "There is no monster in slot ${event.packet.slotIndex}.")
    editOffer(me) { offer ->
      if (mon.id in offer.monIds) {
        offer.copy(monIds = offer.monIds - mon.id)
      } else if (offer.monIds.size >= TradeSwap.MAX_OFFERED_MONS) {
        null
      } else {
        offer.copy(monIds = offer.monIds + mon.id)
      }
    }
  }

  fun onOfferItem(event: PacketEvent<TradeOfferItemPacket>) {
    val me = event.session.charId() ?: return
    val stored = characterStore.getCharacter(me) ?: return
    val p = event.packet
    if (p.amount < 0) return tell(me, "Amounts cannot be negative.")
    when (p.kind) {
      TradeOfferKind.MONEY -> {
        if (p.amount > stored.info.money) return tell(me, "You only have ${stored.info.money}.")
        editOffer(me) { it.copy(money = p.amount) }
      }
      TradeOfferKind.ITEM -> {
        val item = items.get(p.id) ?: return tell(me, "Item ${p.id} does not exist.")
        // Key items have no price and are bound to their owner.
        if (item.price <= 0) return tell(me, "${item.name} cannot be traded.")
        val held = stored.items[p.id] ?: 0
        if (p.amount > held) return tell(me, "You only hold $held ${item.name}.")
        editOffer(me) { offer ->
          val next = offer.items.toMutableMap()
          if (p.amount == 0) next.remove(p.id) else next[p.id] = p.amount
          offer.copy(items = next)
        }
      }
      else -> log.warn { "char=$me offered unknown kind ${p.kind}" }
    }
  }

  /** A disconnect ends the player's trade. A swap already being written still completes. */
  fun onDisconnect(charId: Long) {
    synchronized(lock) { trades[charId]?.takeIf { it.committing }?.abandonedBy = charId }
    close(charId, "left the game")
  }

  fun isTrading(charId: Long): Boolean = synchronized(lock) { charId in trades }

  private fun accept(me: Long) {
    val pending = synchronized(lock) { trades[me] } ?: return
    val a = characterStore.getCharacter(pending.target)
    val b = characterStore.getCharacter(pending.initiator)
    if (a != null && b != null) {
      proximityReason(a, b)?.let {
        return tell(me, it)
      }
    }
    val trade =
        synchronized(lock) {
          val trade = trades[me] ?: return
          if (trade.open || trade.target != me) return
          trade.open = true
          trade
        }
    log.info { "char=$me accepted the trade with char=${trade.initiator}" }
    broadcast(trade)
  }

  private fun ready(me: Long) {
    val trade =
        synchronized(lock) {
          val trade = trades[me] ?: return
          if (!trade.open || trade.committing) return
          if (trade.offers.values.all { it.isEmpty }) return tell(me, "Put something up first.")
          if (trade.stages[me] != TradeStage.NONE) return
          trade.stages[me] = TradeStage.READY
          trade
        }
    broadcast(trade)
  }

  private suspend fun confirm(me: Long) {
    val trade =
        synchronized(lock) {
          val trade = trades[me] ?: return
          if (!trade.open || trade.committing) return
          val partner = trade.partnerOf(me)
          if (trade.stages[me] != TradeStage.READY || trade.stages[partner] == TradeStage.NONE) {
            return tell(me, "Both sides have to be ready before confirming.")
          }
          trade.stages[me] = TradeStage.CONFIRMED
          if (trade.stages[partner] == TradeStage.CONFIRMED) trade.committing = true
          trade
        }
    if (!trade.committing) {
      broadcast(trade)
      return
    }
    commit(trade)
  }

  private suspend fun commit(trade: Trade) {
    val a = trade.initiator
    val b = trade.target
    // Offers cannot change while committing, so reading them outside the lock is safe.
    val offerA = trade.offers.getValue(a)
    val offerB = trade.offers.getValue(b)
    var reason: String? = busyReason(a, nameOf(a), ignoreTrade = true)
    reason = reason ?: busyReason(b, nameOf(b), ignoreTrade = true)
    var ok = false
    var gaveA = ""
    var gaveB = ""
    var receivedByA = emptyList<Long>()
    var receivedByB = emptyList<Long>()
    if (reason == null) {
      ok =
          characterStore.commitPair(a, b) { ca, cb ->
            when (val result = TradeSwap.apply(ca, offerA, cb, offerB)) {
              is SwapResult.Ok -> {
                gaveA = summary(ca, offerA)
                gaveB = summary(cb, offerB)
                receivedByA = offerB.monIds
                receivedByB = offerA.monIds
                result.a to result.b
              }
              is SwapResult.Rejected -> {
                reason = result.reason
                null
              }
            }
          }
      if (!ok && reason == null) reason = "The trade could not be saved."
    }
    val abandonedBy =
        synchronized(lock) {
          trade.committing = false
          if (ok) {
            trades.remove(a, trade)
            trades.remove(b, trade)
          } else {
            trade.resetStages()
          }
          trade.abandonedBy
        }
    if (!ok) {
      log.info { "trade char=$a <-> char=$b refused: $reason" }
      if (abandonedBy != null) {
        close(abandonedBy, "left the game")
      } else {
        broadcast(trade, reason.orEmpty())
      }
      return
    }
    log.info { "trade char=$a <-> char=$b done: $offerA for $offerB" }
    runCatching { history.recordTrade(a, b, gaveA, gaveB, LocalDateTime.now()) }
        .onFailure { log.warn(it) { "Could not log the trade" } }
    for ((receiver, ids) in listOf(a to receivedByA, b to receivedByB)) {
      val owned = characterStore.getCharacter(receiver) ?: continue
      for (id in ids) {
        val mon = (owned.pokemon + owned.pcStorage).firstOrNull { it.id == id } ?: continue
        runCatching { evolution.onTraded(receiver, mon) }
            .onFailure { log.warn(it) { "onTraded hook failed for $id" } }
      }
    }
    for (charId in listOf(a, b)) {
      val session = sessions.getByCharacterId(charId) ?: continue
      val stored = characterStore.getCharacter(charId) ?: continue
      session.send(
          TradeStatePacket(
              phase = TradePhase.COMPLETED,
              partnerName = nameOf(trade.partnerOf(charId)),
              message = "Trade complete.",
          ))
      worldState.send(session, stored, fullVars = true)
      session.send(notice("Trade with ${nameOf(trade.partnerOf(charId))} complete."))
    }
  }

  private fun editOffer(me: Long, change: (TradeOffer) -> TradeOffer?) {
    val trade =
        synchronized(lock) {
          val trade = trades[me] ?: return tell(me, "You are not trading.")
          if (!trade.open) return tell(me, "The trade has not been accepted yet.")
          if (trade.committing) return
          val next = change(trade.offers.getValue(me)) ?: return tell(me, "That cannot be offered.")
          if (next == trade.offers[me]) return
          trade.offers[me] = next
          // Any change voids what either side agreed to.
          trade.resetStages()
          trade
        }
    broadcast(trade)
  }

  private fun close(me: Long, why: String) {
    val trade = synchronized(lock) { trades[me] } ?: return
    log.info { "trade char=${trade.initiator} <-> char=${trade.target}: char=$me $why" }
    close(trade, "${nameOf(me)} $why.")
  }

  private fun close(trade: Trade, message: String) {
    synchronized(lock) {
      if (trade.committing) return
      if (trades[trade.initiator] !== trade && trades[trade.target] !== trade) return
      trades.remove(trade.initiator, trade)
      trades.remove(trade.target, trade)
    }
    for (charId in listOf(trade.initiator, trade.target)) {
      sessions
          .getByCharacterId(charId)
          ?.send(
              TradeStatePacket(
                  TradePhase.CLOSED,
                  partnerName = nameOf(trade.partnerOf(charId)),
                  message = message))
    }
  }

  /** Why [charId] cannot start or finish a trade right now, or null when it can. */
  private fun busyReason(charId: Long, name: String, ignoreTrade: Boolean = false): String? {
    val session = sessions.getByCharacterId(charId) ?: return "$name is not online."
    val state = session.attributes[PLAYER_STATE] ?: return "$name is not online."
    if (characterStore.getCharacter(charId) == null) return "$name is not online."
    if (battleService.inBattle(charId)) return "$name is in a battle."
    // A running script snapshots the character and rolls back to that snapshot when interrupted,
    // which would hand a traded monster back as well.
    if (state.inDialog) return "$name is busy."
    if (!ignoreTrade && isTrading(charId)) return "$name is already trading."
    return null
  }

  /**
   * Snapshot and send under the same lock. Two changes broadcast from different threads would
   * otherwise be able to reach a client in the opposite order, leaving it on the older state (seen
   * as one side stuck on "partner not ready"). Netty preserves the order of writes queued from one
   * thread at a time, so serialising the sends is enough.
   */
  private fun broadcast(trade: Trade, message: String = "") =
      synchronized(lock) {
        val open = trade.open
        val offers = trade.offers.toMap()
        val stages = trade.stages.toMap()
        for (charId in listOf(trade.initiator, trade.target)) {
          val partner = trade.partnerOf(charId)
          val phase =
              when {
                open -> TradePhase.OPEN
                charId == trade.initiator -> TradePhase.REQUEST_SENT
                else -> TradePhase.REQUEST_RECEIVED
              }
          sessions
              .getByCharacterId(charId)
              ?.send(
                  TradeStatePacket(
                      phase = phase,
                      partnerName = nameOf(partner),
                      mine = view(charId, offers.getValue(charId)),
                      theirs = view(partner, offers.getValue(partner)),
                      myStage = stages.getValue(charId),
                      theirStage = stages.getValue(partner),
                      message = message,
                      expiresInSeconds = if (open) 0 else expiresIn(trade),
                  ))
        }
      }

  private fun view(owner: Long, offer: TradeOffer): TradeOfferView {
    val stored: StoredCharacter? = characterStore.getCharacter(owner)
    val party = stored?.pokemon?.sortedBy { it.containerSlot }.orEmpty()
    val pc = stored?.pcStorage.orEmpty()
    val mons =
        offer.monIds.mapNotNull { id ->
          val index = party.indexOfFirst { it.id == id }
          party.getOrNull(index)?.let {
            TradeMonView(index, it.dexId, it.level.toInt(), it.nickname, it.isShiny, it.hp.toInt())
          }
        }
    val pcMons =
        offer.monIds.mapNotNull { id ->
          pc.firstOrNull { it.id == id }
              ?.let {
                TradeMonView(
                    it.containerSlot.toInt(),
                    it.dexId,
                    it.level.toInt(),
                    it.nickname,
                    it.isShiny,
                    it.hp.toInt())
              }
        }
    return TradeOfferView(
        mons,
        offer.items.map { (id, qty) -> TradeItemView(id, qty) }.sortedBy { it.itemId },
        offer.money,
        pcMons)
  }

  /** "PIKACHU Lv12, 3x Potion, 500 money" for the trade log. */
  private fun summary(owner: StoredCharacter, offer: TradeOffer): String {
    val all = owner.pokemon + owner.pcStorage
    val parts =
        offer.monIds.mapNotNull { id ->
          all.firstOrNull { it.id == id }
              ?.let { m ->
                val name = species.get(m.dexId)?.name ?: "#${m.dexId}"
                "$name Lv${m.level}"
              }
        } +
            offer.items.entries
                .sortedBy { it.key }
                .map { (id, qty) -> "${qty}x ${items.get(id)?.name ?: "item $id"}" } +
            listOfNotNull(offer.money.takeIf { it > 0 }?.let { "$it money" })
    return if (parts.isEmpty()) "nothing" else parts.joinToString(", ")
  }

  private fun expiresIn(trade: Trade): Int {
    val left = trade.requestedAtMs + config.tradeRequestSeconds * 1000L - System.currentTimeMillis()
    return ((left + 999) / 1000).toInt().coerceAtLeast(0)
  }

  private fun nameOf(charId: Long): String = characterStore.getCharacter(charId)?.info?.name ?: "?"

  private fun tell(charId: Long, message: String) {
    sessions.getByCharacterId(charId)?.send(notice(message))
  }

  private fun refuse(session: SessionContext, message: String) {
    session.send(notice(message))
    session.send(TradeStatePacket(TradePhase.CLOSED, message = message))
  }

  private fun SessionContext.charId(): Long? = attributes[PLAYER_STATE]?.characterId
}
