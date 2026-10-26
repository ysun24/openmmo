package de.fiereu.openmmo.server.game.services

import de.fiereu.network.PacketEvent
import de.fiereu.network.SessionContext
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.net.game.packets.CreateMarketListingPacket
import de.fiereu.openmmo.net.game.packets.CurrencyBalancePacket
import de.fiereu.openmmo.net.game.packets.LocalCharacterDeltaPacket
import de.fiereu.openmmo.net.game.packets.PokemonContainerPacket
import de.fiereu.openmmo.net.game.packets.gtl.GtlConfirmPurchasePacket
import de.fiereu.openmmo.net.game.packets.gtl.GtlFilterKind
import de.fiereu.openmmo.net.game.packets.gtl.GtlItemListing
import de.fiereu.openmmo.net.game.packets.gtl.GtlListKind
import de.fiereu.openmmo.net.game.packets.gtl.GtlListingCancelPacket
import de.fiereu.openmmo.net.game.packets.gtl.GtlPokemonListing
import de.fiereu.openmmo.net.game.packets.gtl.GtlRawFilter
import de.fiereu.openmmo.net.game.packets.gtl.GtlSearchListing
import de.fiereu.openmmo.net.game.packets.gtl.GtlSearchPagePacket
import de.fiereu.openmmo.net.game.packets.gtl.GtlSearchPageRequestPacket
import de.fiereu.openmmo.net.game.packets.gtl.GtlSpeciesFilter
import de.fiereu.openmmo.net.game.packets.market.MarketAction
import de.fiereu.openmmo.net.game.packets.market.MarketInventoryPacket
import de.fiereu.openmmo.net.game.packets.market.MarketInventoryRequestPacket
import de.fiereu.openmmo.net.game.packets.market.MarketItemStack
import de.fiereu.openmmo.net.game.packets.market.MarketResultPacket
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import de.fiereu.openmmo.server.game.battle.BattleRegistry
import de.fiereu.openmmo.server.game.battle.StatCalculator
import de.fiereu.openmmo.server.game.session.PLAYER_STATE
import de.fiereu.openmmo.server.game.session.SessionRegistry
import de.fiereu.openmmo.server.game.storage.CharacterStore
import de.fiereu.openmmo.server.game.storage.MarketKind
import de.fiereu.openmmo.server.game.storage.MarketListing
import de.fiereu.openmmo.server.game.storage.MarketOutcome
import de.fiereu.openmmo.server.game.storage.MarketQuery
import de.fiereu.openmmo.server.game.storage.MarketRepository
import de.fiereu.openmmo.server.game.storage.MarketRow
import de.fiereu.openmmo.server.game.storage.MarketSort
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val log = KotlinLogging.logger {}

private const val MAX_PRICE = 999_999_999
private const val MAX_ACTIVE_LISTINGS = 30
private const val PAGE_SIZE = 20
private val EXPIRY_SWEEP = 60.seconds

/** Wire values of [CreateMarketListingPacket.entryKind]. */
const val MARKET_ENTRY_POKEMON: Byte = 0
const val MARKET_ENTRY_ITEM: Byte = 1

/**
 * The global market. Listing moves the goods into escrow, buying pays the seller straight into
 * their character row whether they are online or not, cancelling and expiry hand the goods back.
 * Each of those is one transaction in [MarketRepository]; this class adds the checks that need the
 * session, keeps the [CharacterStore] cache in step and tells everyone involved what happened.
 *
 * Expired listings are returned lazily before every browse and by a sweep every minute.
 */
@Singleton
class MarketService
@Inject
constructor(
    private val repository: MarketRepository,
    private val characterStore: CharacterStore,
    private val sessions: SessionRegistry,
    private val battles: BattleRegistry,
    private val items: ItemRegistry,
    private val species: SpeciesRegistry,
    private val scope: CoroutineScope,
) {
  private val sweepStarted = AtomicBoolean(false)

  /** How long a listing runs. `-Dmarket.listingSeconds` overrides it, for testing expiry. */
  private val listingSeconds: Long =
      System.getProperty("market.listingSeconds")?.toLongOrNull()
          ?: System.getenv("MARKET_LISTING_SECONDS")?.toLongOrNull()
          ?: (7L * 24 * 3600)

  private fun now(): LocalDateTime = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS)

  suspend fun onCreate(event: PacketEvent<CreateMarketListingPacket>) {
    val session = event.session
    val p = event.packet
    val charId = actingCharacter(session, MarketAction.CREATE) ?: return
    if (p.price !in 1..MAX_PRICE) {
      return reply(session, MarketAction.CREATE, false, 0, "Price must be 1 to $MAX_PRICE.")
    }
    val now = now()
    val expires = now.plusSeconds(listingSeconds)
    val outcome =
        when (p.entryKind) {
          MARKET_ENTRY_POKEMON ->
              characterStore.withExternalWrite(listOf(charId)) {
                repository.createPokemonListing(
                    charId, p.entityId, p.price, now, expires, MAX_ACTIVE_LISTINGS)
              }
          MARKET_ENTRY_ITEM -> {
            val itemId = p.entityId.toInt()
            val qty = p.quantity.toInt()
            if (qty <= 0 || items.get(itemId) == null) {
              return reply(session, MarketAction.CREATE, false, 0, "No such item or quantity.")
            }
            characterStore.withExternalWrite(listOf(charId)) {
              repository.createItemListing(
                  charId, itemId, qty, p.price, now, expires, MAX_ACTIVE_LISTINGS)
            }
          }
          else -> MarketOutcome.Refused("Unknown listing kind ${p.entryKind}.")
        }
    when (outcome) {
      is MarketOutcome.Refused -> reply(session, MarketAction.CREATE, false, 0, outcome.reason)
      is MarketOutcome.Done -> {
        val l = outcome.listing
        log.info { "char=$charId listed ${describe(l)} for ${l.price} as #${l.id}" }
        reply(session, MarketAction.CREATE, true, l.id, "Listed ${describe(l)} for ${l.price}.")
        sendInventory(session, charId, partyChanged = l.kind == MarketKind.POKEMON)
      }
    }
  }

  suspend fun onBuy(event: PacketEvent<GtlConfirmPurchasePacket>) {
    val session = event.session
    val buyerId = actingCharacter(session, MarketAction.BUY) ?: return
    val listingId = event.packet.listingEntityId
    val sellerId =
        repository.sellerOf(listingId)
            ?: return reply(session, MarketAction.BUY, false, listingId, "No such listing.")
    val outcome =
        characterStore.withExternalWrite(listOf(buyerId, sellerId)) {
          repository.buy(listingId, buyerId, now())
        }
    when (outcome) {
      is MarketOutcome.Refused -> {
        reply(session, MarketAction.BUY, false, listingId, outcome.reason)
        if (outcome.reason.contains("expired")) sweepExpired()
      }
      is MarketOutcome.Done -> {
        val l = outcome.listing
        log.info {
          "char=$buyerId bought #${l.id} ${describe(l)} from char=$sellerId for ${l.price}"
        }
        reply(session, MarketAction.BUY, true, l.id, "Bought ${describe(l)} for ${l.price}.")
        sendInventory(session, buyerId, partyChanged = l.kind == MarketKind.POKEMON)
        sessions.getByCharacterId(sellerId)?.let { seller ->
          reply(seller, MarketAction.SOLD, true, l.id, "Sold ${describe(l)} for ${l.price}.")
          sendInventory(seller, sellerId, partyChanged = false)
        }
      }
    }
  }

  suspend fun onCancel(event: PacketEvent<GtlListingCancelPacket>) {
    val session = event.session
    val charId = actingCharacter(session, MarketAction.CANCEL) ?: return
    val listingId =
        event.packet.listingId.trim().toLongOrNull()
            ?: return reply(session, MarketAction.CANCEL, false, 0, "Bad listing id.")
    val outcome =
        characterStore.withExternalWrite(listOf(charId)) {
          repository.cancel(listingId, charId, now())
        }
    when (outcome) {
      is MarketOutcome.Refused ->
          reply(session, MarketAction.CANCEL, false, listingId, outcome.reason)
      is MarketOutcome.Done -> {
        val l = outcome.listing
        log.info { "char=$charId cancelled #${l.id}" }
        reply(session, MarketAction.CANCEL, true, l.id, "Cancelled, ${describe(l)} returned.")
        sendInventory(session, charId, partyChanged = l.kind == MarketKind.POKEMON)
      }
    }
  }

  suspend fun onSearch(event: PacketEvent<GtlSearchPageRequestPacket>) {
    val session = event.session
    val charId = session.attributes[PLAYER_STATE]?.characterId ?: return
    ensureSweep()
    sweepExpired()
    val p = event.packet
    val refIds =
        p.filters.filterIsInstance<GtlSpeciesFilter>().flatMap { f ->
          f.speciesIds.map { it.toInt() }
        }
    fun priceFilter(kind: GtlFilterKind): Int? =
        p.filters
            .filterIsInstance<GtlRawFilter>()
            .firstOrNull { it.kind == kind && it.payload.size == 4 }
            ?.let { ByteBuffer.wrap(it.payload).order(ByteOrder.LITTLE_ENDIAN).int }
    val query =
        MarketQuery(
            kind =
                when (p.listKind) {
                  GtlListKind.POKEMON -> MarketKind.POKEMON
                  GtlListKind.ITEM -> MarketKind.ITEM
                  GtlListKind.OWN_LISTINGS -> null
                },
            refIds = refIds,
            minPrice = priceFilter(GtlFilterKind.MIN_PRICE),
            maxPrice = priceFilter(GtlFilterKind.MAX_PRICE),
            sellerId = if (p.listKind == GtlListKind.OWN_LISTINGS) charId else null,
            sort = MarketSort.entries.getOrElse(p.categoryId.toInt()) { MarketSort.PRICE_ASC },
            page = p.page.toInt().coerceAtLeast(0),
            pageSize = PAGE_SIZE,
        )
    val page = repository.search(query, now())
    session.send(
        GtlSearchPagePacket(
            requestId = p.requestId,
            listKind = p.listKind,
            page = p.page,
            totalMatches = page.total,
            listings = page.rows.mapNotNull(::toWire),
            quotes = if (p.listKind == GtlListKind.ITEM) emptyList() else null,
        ))
  }

  fun onInventory(event: PacketEvent<MarketInventoryRequestPacket>) {
    val session = event.session
    val charId = session.attributes[PLAYER_STATE]?.characterId ?: return
    ensureSweep()
    sendInventory(session, charId, partyChanged = false)
  }

  /** Returns every listing that ran out to its seller. */
  suspend fun sweepExpired() {
    val now = now()
    for ((listingId, sellerId) in repository.dueForExpiry(now)) {
      val outcome =
          characterStore.withExternalWrite(listOf(sellerId)) { repository.expire(listingId, now) }
      if (outcome !is MarketOutcome.Done) continue
      val l = outcome.listing
      log.info { "Listing #${l.id} of char=$sellerId expired, goods returned" }
      sessions.getByCharacterId(sellerId)?.let { seller ->
        reply(seller, MarketAction.EXPIRED, true, l.id, "Listing expired, ${describe(l)} returned.")
        sendInventory(seller, sellerId, partyChanged = l.kind == MarketKind.POKEMON)
      }
    }
  }

  private fun ensureSweep() {
    if (!sweepStarted.compareAndSet(false, true)) return
    scope.launch {
      while (isActive) {
        delay(EXPIRY_SWEEP)
        runCatching { sweepExpired() }.onFailure { log.warn(it) { "Market expiry sweep failed" } }
      }
    }
  }

  /** The character, if it may trade right now. Replies with the reason when it may not. */
  private fun actingCharacter(session: SessionContext, action: MarketAction): Long? {
    val state = session.attributes[PLAYER_STATE] ?: return null
    val charId = state.characterId ?: return null
    ensureSweep()
    val reason =
        when {
          characterStore.getCharacter(charId) == null -> "You are not in the world yet."
          // A script rolled back later would restore the goods from its snapshot.
          state.inDialog -> "Finish your conversation first."
          battles.byChar(charId) != null -> "You cannot trade during a battle."
          else -> null
        }
    if (reason != null) {
      reply(session, action, false, 0, reason)
      return null
    }
    return charId
  }

  private fun reply(
      session: SessionContext,
      action: MarketAction,
      success: Boolean,
      listingId: Long,
      message: String,
  ) {
    session.send(MarketResultPacket(action, success, listingId, message))
    session.send(notice("[Market] $message"))
  }

  private fun sendInventory(session: SessionContext, charId: Long, partyChanged: Boolean) {
    val stored = characterStore.getCharacter(charId) ?: return
    val party = stored.pokemon.sortedBy { it.containerSlot }
    session.send(CurrencyBalancePacket(stored.info.money, 0))
    session.send(LocalCharacterDeltaPacket(money = stored.info.money))
    if (partyChanged) {
      session.send(PokemonContainerPacket(PokemonContainer.PARTY, true, false, party))
    }
    session.send(
        MarketInventoryPacket(
            money = stored.info.money,
            party = party,
            pcCount = stored.pcStorage.size.toShort(),
            items =
                stored.items.entries
                    .sortedBy { it.key }
                    .map { MarketItemStack(it.key.toShort(), it.value) },
        ))
  }

  private fun toWire(row: MarketRow): GtlSearchListing? {
    val l = row.listing
    val listedAt = l.createdAt.toEpochSecond(ZoneOffset.UTC).toInt()
    val expiresAt = l.expiresAt.toEpochSecond(ZoneOffset.UTC).toInt()
    return when (l.kind) {
      MarketKind.ITEM ->
          GtlItemListing(
              l.id, l.price, listedAt, expiresAt, l.quantity.toShort(), l.itemId!!.toShort(), 0)
      MarketKind.POKEMON -> {
        val mon = row.pokemon ?: return null
        val stats =
            species.get(mon.dexId)?.let { def ->
              val s = StatCalculator.computeAll(def, mon)
              // Board order: hp, atk, def, spd, spAtk, spDef.
              listOf(s.hp, s.atk, s.def, s.spd, s.spAtk, s.spDef).map { it.toShort() }
            } ?: List(6) { 0.toShort() }
        GtlPokemonListing(l.id, l.price, listedAt, expiresAt, 1, mon, stats)
      }
    }
  }

  private fun describe(l: MarketListing): String =
      when (l.kind) {
        MarketKind.POKEMON -> species.get(l.dexId ?: 0)?.name ?: "pokemon #${l.dexId}"
        MarketKind.ITEM -> "${l.quantity}x ${items.get(l.itemId ?: 0)?.name ?: "item ${l.itemId}"}"
      }
}
