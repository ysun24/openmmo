package de.fiereu.openmmo.server.game.storage

import de.fiereu.openmmo.common.MAX_PARTY_SIZE
import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.PokemonMove
import de.fiereu.openmmo.common.enums.EVs
import de.fiereu.openmmo.common.enums.IVs
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.db.game.tables.records.MarketListingsRecord
import de.fiereu.openmmo.db.game.tables.records.PokemonRecord
import de.fiereu.openmmo.db.game.tables.references.CHARACTERS
import de.fiereu.openmmo.db.game.tables.references.CHARACTER_ITEMS
import de.fiereu.openmmo.db.game.tables.references.MARKET_LISTINGS
import de.fiereu.openmmo.db.game.tables.references.POKEMON
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.SortField
import org.jooq.impl.DSL

enum class MarketKind {
  POKEMON,
  ITEM,
}

enum class MarketStatus {
  ACTIVE,
  SOLD,
  CANCELLED,
  EXPIRED,
}

enum class MarketSort {
  PRICE_ASC,
  PRICE_DESC,
  NEWEST,
}

data class MarketListing(
    val id: Long,
    val sellerId: Long,
    val kind: MarketKind,
    val pokemonId: Long?,
    val dexId: Int?,
    val itemId: Int?,
    val quantity: Int,
    val price: Int,
    val createdAt: LocalDateTime,
    val expiresAt: LocalDateTime,
    val status: MarketStatus,
    val buyerId: Long?,
    val soldAt: LocalDateTime?,
    /** What the seller paid to list it (V8_4). */
    val fee: Int = 0,
)

/** A listing a search returned, with the escrowed monster when it is one, and who sells it. */
data class MarketRow(
    val listing: MarketListing,
    val pokemon: Pokemon?,
    val sellerName: String = ""
)

/** One completed sale, for price history: the whole price and how many units it bought. */
data class MarketSale(val soldAt: LocalDateTime, val price: Int, val quantity: Int)

/** One of a seller's sold listings, with who bought it. */
data class SoldListing(val listing: MarketListing, val buyerName: String)

data class MarketPage(val total: Int, val rows: List<MarketRow>)

data class MarketQuery(
    val kind: MarketKind? = null,
    /** Dex ids for a monster search, item ids for an item search. Empty means any. */
    val refIds: List<Int> = emptyList(),
    val minPrice: Int? = null,
    val maxPrice: Int? = null,
    val sellerId: Long? = null,
    /** Hides a seller's own listings from a browse. */
    val excludeSellerId: Long? = null,
    val sort: MarketSort = MarketSort.PRICE_ASC,
    val page: Int = 0,
    val pageSize: Int = 20,
)

/** Why a market action was refused. The message is shown to the player as is. */
class MarketRefused(message: String) : Exception(message)

sealed interface MarketOutcome {
  data class Done(val listing: MarketListing) : MarketOutcome

  data class Refused(val reason: String) : MarketOutcome
}

/**
 * The market's database side. Every action is one transaction: a listing row and both characters
 * are locked with `SELECT ... FOR UPDATE` (listing first, then characters in id order) before
 * anything is checked, so two buyers racing for one listing serialise on its row and the second
 * sees it already sold.
 */
@Singleton
class MarketRepository
@Inject
constructor(
    private val dsl: DSLContext,
    @param:Named("db") private val dispatcher: CoroutineDispatcher,
) {

  suspend fun sellerOf(listingId: Long): Long? =
      withContext(dispatcher) {
        dsl.select(MARKET_LISTINGS.SELLER_ID)
            .from(MARKET_LISTINGS)
            .where(MARKET_LISTINGS.ID.eq(listingId))
            .fetchOne(MARKET_LISTINGS.SELLER_ID)
      }

  suspend fun createPokemonListing(
      sellerId: Long,
      pokemonId: Long,
      price: Int,
      now: LocalDateTime,
      expiresAt: LocalDateTime,
      maxActive: Int,
      fee: Int = 0,
  ): MarketOutcome = inTransaction { tx ->
    val money = lockCharacters(tx, listOf(sellerId))
    checkActiveLimit(tx, sellerId, maxActive)
    chargeFee(tx, sellerId, money[sellerId] ?: 0, fee)
    val mon =
        tx.selectFrom(POKEMON)
            .where(POKEMON.ID.eq(pokemonId))
            .and(POKEMON.OWNER_ID.eq(sellerId))
            .forUpdate()
            .fetchOne() ?: throw MarketRefused("You do not have that pokemon.")
    val from = PokemonContainer.valueOf(mon.container)
    if (from != PokemonContainer.PARTY && from != PokemonContainer.PC) {
      throw MarketRefused("That pokemon cannot be listed.")
    }
    if (from == PokemonContainer.PARTY && partyCount(tx, sellerId) <= 1) {
      throw MarketRefused("You cannot list your last party pokemon.")
    }
    val escrowSlot = nextSlot(tx, sellerId, PokemonContainer.GTS)
    tx.update(POKEMON)
        .set(POKEMON.CONTAINER, PokemonContainer.GTS.name)
        .set(POKEMON.CONTAINER_SLOT, escrowSlot)
        .where(POKEMON.ID.eq(pokemonId))
        .execute()
    if (from == PokemonContainer.PARTY) compactParty(tx, sellerId)
    insertListing(
        tx, sellerId, MarketKind.POKEMON, pokemonId, mon.dexId, null, 1, price, now, expiresAt, fee)
  }

  suspend fun createItemListing(
      sellerId: Long,
      itemId: Int,
      quantity: Int,
      price: Int,
      now: LocalDateTime,
      expiresAt: LocalDateTime,
      maxActive: Int,
      fee: Int = 0,
  ): MarketOutcome = inTransaction { tx ->
    val money = lockCharacters(tx, listOf(sellerId))
    checkActiveLimit(tx, sellerId, maxActive)
    chargeFee(tx, sellerId, money[sellerId] ?: 0, fee)
    if (!takeItem(tx, sellerId, itemId, quantity)) {
      throw MarketRefused("You do not have $quantity of item $itemId.")
    }
    insertListing(
        tx, sellerId, MarketKind.ITEM, null, null, itemId, quantity, price, now, expiresAt, fee)
  }

  /** Pays the seller and hands the goods over. Refuses an expired listing without closing it. */
  suspend fun buy(listingId: Long, buyerId: Long, now: LocalDateTime): MarketOutcome =
      inTransaction { tx ->
        val row = lockListing(tx, listingId) ?: throw MarketRefused("No such listing.")
        val listing = row.toListing()
        if (listing.status != MarketStatus.ACTIVE) {
          throw MarketRefused("That listing is no longer for sale.")
        }
        if (!listing.expiresAt.isAfter(now)) throw MarketRefused("That listing has expired.")
        if (listing.sellerId == buyerId) throw MarketRefused("You cannot buy your own listing.")
        val money = lockCharacters(tx, listOf(buyerId, listing.sellerId))
        val buyerMoney = money[buyerId] ?: throw MarketRefused("Unknown buyer.")
        val sellerMoney = money[listing.sellerId] ?: 0
        if (buyerMoney < listing.price) throw MarketRefused("You cannot afford ${listing.price}.")
        if (sellerMoney.toLong() + listing.price > Int.MAX_VALUE) {
          throw MarketRefused("The seller cannot hold any more money.")
        }
        tx.update(CHARACTERS)
            .set(CHARACTERS.MONEY, CHARACTERS.MONEY.minus(listing.price))
            .where(CHARACTERS.ID.eq(buyerId))
            .execute()
        tx.update(CHARACTERS)
            .set(CHARACTERS.MONEY, CHARACTERS.MONEY.plus(listing.price))
            .where(CHARACTERS.ID.eq(listing.sellerId))
            .execute()
        deliver(tx, listing, buyerId)
        tx.update(MARKET_LISTINGS)
            .set(MARKET_LISTINGS.STATUS, MarketStatus.SOLD.name)
            .set(MARKET_LISTINGS.BUYER_ID, buyerId)
            .set(MARKET_LISTINGS.SOLD_AT, now)
            .set(MARKET_LISTINGS.CLOSED_AT, now)
            .where(MARKET_LISTINGS.ID.eq(listingId))
            .execute()
        listing.copy(status = MarketStatus.SOLD, buyerId = buyerId, soldAt = now)
      }

  suspend fun cancel(listingId: Long, sellerId: Long, now: LocalDateTime): MarketOutcome =
      close(listingId, now, MarketStatus.CANCELLED) { listing ->
        if (listing.sellerId != sellerId) throw MarketRefused("That is not your listing.")
      }

  /** Returns the goods of a listing whose time ran out. Refuses one that has not. */
  suspend fun expire(listingId: Long, now: LocalDateTime): MarketOutcome =
      close(listingId, now, MarketStatus.EXPIRED) { listing ->
        if (listing.expiresAt.isAfter(now)) throw MarketRefused("That listing is still running.")
      }

  /** Active listings past their expiry, as (listing id, seller id). */
  suspend fun dueForExpiry(now: LocalDateTime, limit: Int = 100): List<Pair<Long, Long>> =
      withContext(dispatcher) {
        dsl.select(MARKET_LISTINGS.ID, MARKET_LISTINGS.SELLER_ID)
            .from(MARKET_LISTINGS)
            .where(MARKET_LISTINGS.STATUS.eq(MarketStatus.ACTIVE.name))
            .and(MARKET_LISTINGS.EXPIRES_AT.le(now))
            .orderBy(MARKET_LISTINGS.EXPIRES_AT)
            .limit(limit)
            .fetch { it.value1()!! to it.value2()!! }
      }

  suspend fun find(listingId: Long): MarketListing? =
      withContext(dispatcher) {
        dsl.selectFrom(MARKET_LISTINGS)
            .where(MARKET_LISTINGS.ID.eq(listingId))
            .fetchOne()
            ?.toListing()
      }

  suspend fun search(query: MarketQuery, now: LocalDateTime): MarketPage =
      withContext(dispatcher) {
        val conditions =
            buildList<Condition> {
              add(MARKET_LISTINGS.STATUS.eq(MarketStatus.ACTIVE.name))
              // Mine shows everything still in escrow, a browse only what can still be bought.
              if (query.sellerId == null) add(MARKET_LISTINGS.EXPIRES_AT.gt(now))
              query.kind?.let { add(MARKET_LISTINGS.KIND.eq(it.name)) }
              if (query.refIds.isNotEmpty()) {
                add(
                    when (query.kind) {
                      MarketKind.ITEM -> MARKET_LISTINGS.ITEM_ID.`in`(query.refIds)
                      MarketKind.POKEMON -> MARKET_LISTINGS.DEX_ID.`in`(query.refIds)
                      null ->
                          MARKET_LISTINGS.ITEM_ID.`in`(query.refIds)
                              .or(MARKET_LISTINGS.DEX_ID.`in`(query.refIds))
                    })
              }
              query.minPrice?.let { add(MARKET_LISTINGS.PRICE.ge(it)) }
              query.maxPrice?.let { add(MARKET_LISTINGS.PRICE.le(it)) }
              query.sellerId?.let { add(MARKET_LISTINGS.SELLER_ID.eq(it)) }
              query.excludeSellerId?.let { add(MARKET_LISTINGS.SELLER_ID.ne(it)) }
            }
        val where = DSL.and(conditions)
        val total = dsl.fetchCount(MARKET_LISTINGS, where)
        val order: List<SortField<*>> =
            when (query.sort) {
              MarketSort.PRICE_ASC -> listOf(MARKET_LISTINGS.PRICE.asc(), MARKET_LISTINGS.ID.asc())
              MarketSort.PRICE_DESC ->
                  listOf(MARKET_LISTINGS.PRICE.desc(), MARKET_LISTINGS.ID.asc())
              MarketSort.NEWEST -> listOf(MARKET_LISTINGS.ID.desc())
            }
        val listings =
            dsl.selectFrom(MARKET_LISTINGS)
                .where(where)
                .orderBy(order)
                .limit(query.pageSize)
                .offset(query.page * query.pageSize)
                .fetch()
                .map { it.toListing() }
        val sellers =
            listings
                .map { it.sellerId }
                .distinct()
                .takeIf { it.isNotEmpty() }
                ?.let { ids ->
                  dsl.select(CHARACTERS.ID, CHARACTERS.NAME)
                      .from(CHARACTERS)
                      .where(CHARACTERS.ID.`in`(ids))
                      .fetch()
                      .associate { it.value1()!! to it.value2()!! }
                }
                .orEmpty()
        val monIds = listings.mapNotNull { it.pokemonId }
        val mons =
            if (monIds.isEmpty()) emptyMap()
            else
                dsl.selectFrom(POKEMON).where(POKEMON.ID.`in`(monIds)).fetch().associate {
                  it.id to it.toPokemon()
                }
        MarketPage(
            total,
            listings.map {
              MarketRow(it, it.pokemonId?.let(mons::get), sellers[it.sellerId].orEmpty())
            })
      }

  private suspend fun close(
      listingId: Long,
      now: LocalDateTime,
      status: MarketStatus,
      check: (MarketListing) -> Unit,
  ): MarketOutcome = inTransaction { tx ->
    val row = lockListing(tx, listingId) ?: throw MarketRefused("No such listing.")
    val listing = row.toListing()
    if (listing.status != MarketStatus.ACTIVE) throw MarketRefused("That listing is closed.")
    check(listing)
    lockCharacters(tx, listOf(listing.sellerId))
    deliver(tx, listing, listing.sellerId)
    tx.update(MARKET_LISTINGS)
        .set(MARKET_LISTINGS.STATUS, status.name)
        .set(MARKET_LISTINGS.CLOSED_AT, now)
        .where(MARKET_LISTINGS.ID.eq(listingId))
        .execute()
    listing.copy(status = status)
  }

  private suspend fun inTransaction(work: (DSLContext) -> MarketListing): MarketOutcome =
      withContext(dispatcher) {
        try {
          MarketOutcome.Done(dsl.transactionResult { cfg -> work(cfg.dsl()) })
        } catch (e: MarketRefused) {
          MarketOutcome.Refused(e.message ?: "Refused.")
        } catch (e: org.jooq.exception.DataAccessException) {
          // jOOQ wraps an exception thrown inside the transaction lambda.
          val refused =
              generateSequence(e as Throwable) { it.cause }.firstOrNull { it is MarketRefused }
          if (refused != null) MarketOutcome.Refused(refused.message ?: "Refused.") else throw e
        }
      }

  private fun lockListing(tx: DSLContext, id: Long): MarketListingsRecord? =
      tx.selectFrom(MARKET_LISTINGS).where(MARKET_LISTINGS.ID.eq(id)).forUpdate().fetchOne()

  /** Locks the character rows in id order and returns their balances. */
  private fun lockCharacters(tx: DSLContext, ids: List<Long>): Map<Long, Int> =
      tx.select(CHARACTERS.ID, CHARACTERS.MONEY)
          .from(CHARACTERS)
          .where(CHARACTERS.ID.`in`(ids.distinct()))
          .orderBy(CHARACTERS.ID)
          .forUpdate()
          .fetch()
          .associate { it.value1()!! to it.value2()!! }

  /** Takes the listing fee; the whole listing is refused when the seller cannot pay it. */
  private fun chargeFee(tx: DSLContext, sellerId: Long, balance: Int, fee: Int) {
    if (fee <= 0) return
    if (balance < fee) throw MarketRefused("The listing fee is $fee.")
    tx.update(CHARACTERS)
        .set(CHARACTERS.MONEY, CHARACTERS.MONEY.minus(fee))
        .where(CHARACTERS.ID.eq(sellerId))
        .execute()
  }

  suspend fun activeCount(sellerId: Long): Int =
      withContext(dispatcher) {
        dsl.fetchCount(
            MARKET_LISTINGS,
            MARKET_LISTINGS.SELLER_ID.eq(sellerId)
                .and(MARKET_LISTINGS.STATUS.eq(MarketStatus.ACTIVE.name)))
      }

  /** Sales of one species or item since [since], newest first. */
  suspend fun sales(
      kind: MarketKind,
      refId: Int,
      since: LocalDateTime,
      limit: Int = 500
  ): List<MarketSale> =
      withContext(dispatcher) {
        val ref =
            if (kind == MarketKind.POKEMON) MARKET_LISTINGS.DEX_ID else MARKET_LISTINGS.ITEM_ID
        dsl.select(MARKET_LISTINGS.SOLD_AT, MARKET_LISTINGS.PRICE, MARKET_LISTINGS.QUANTITY)
            .from(MARKET_LISTINGS)
            .where(MARKET_LISTINGS.STATUS.eq(MarketStatus.SOLD.name))
            .and(MARKET_LISTINGS.KIND.eq(kind.name))
            .and(ref.eq(refId))
            .and(MARKET_LISTINGS.SOLD_AT.ge(since))
            .orderBy(MARKET_LISTINGS.SOLD_AT.desc())
            .limit(limit)
            .fetch { MarketSale(it.value1()!!, it.value2()!!, it.value3()!!) }
      }

  /** A seller's sold listings, newest first, with the buyer's name. */
  suspend fun soldBy(sellerId: Long, limit: Int): List<SoldListing> =
      withContext(dispatcher) {
        dsl.select(MARKET_LISTINGS.asterisk(), CHARACTERS.NAME)
            .from(MARKET_LISTINGS)
            .leftJoin(CHARACTERS)
            .on(CHARACTERS.ID.eq(MARKET_LISTINGS.BUYER_ID))
            .where(MARKET_LISTINGS.SELLER_ID.eq(sellerId))
            .and(MARKET_LISTINGS.STATUS.eq(MarketStatus.SOLD.name))
            .orderBy(MARKET_LISTINGS.SOLD_AT.desc())
            .limit(limit)
            .fetch { r ->
              SoldListing(r.into(MARKET_LISTINGS).toListing(), r.get(CHARACTERS.NAME) ?: "?")
            }
      }

  private fun checkActiveLimit(tx: DSLContext, sellerId: Long, maxActive: Int) {
    val active =
        tx.fetchCount(
            MARKET_LISTINGS,
            MARKET_LISTINGS.SELLER_ID.eq(sellerId)
                .and(MARKET_LISTINGS.STATUS.eq(MarketStatus.ACTIVE.name)))
    if (active >= maxActive) throw MarketRefused("You already have $maxActive listings.")
  }

  /** Puts the goods of [listing] with [ownerId]: a monster in the party if it has room, else pc. */
  private fun deliver(tx: DSLContext, listing: MarketListing, ownerId: Long) {
    when (listing.kind) {
      MarketKind.POKEMON -> {
        val monId = listing.pokemonId ?: throw MarketRefused("The listed pokemon is gone.")
        val toParty = partyCount(tx, ownerId) < MAX_PARTY_SIZE
        val container = if (toParty) PokemonContainer.PARTY else PokemonContainer.PC
        val slot = nextSlot(tx, ownerId, container)
        tx.update(POKEMON)
            .set(POKEMON.OWNER_ID, ownerId)
            .set(POKEMON.CONTAINER, container.name)
            .set(POKEMON.CONTAINER_SLOT, slot)
            .where(POKEMON.ID.eq(monId))
            .execute()
      }
      MarketKind.ITEM ->
          tx.insertInto(CHARACTER_ITEMS)
              .set(CHARACTER_ITEMS.CHARACTER_ID, ownerId)
              .set(CHARACTER_ITEMS.ITEM_ID, listing.itemId!!)
              .set(CHARACTER_ITEMS.QUANTITY, listing.quantity)
              .onConflict(CHARACTER_ITEMS.CHARACTER_ID, CHARACTER_ITEMS.ITEM_ID)
              .doUpdate()
              .set(CHARACTER_ITEMS.QUANTITY, CHARACTER_ITEMS.QUANTITY.plus(listing.quantity))
              .execute()
    }
  }

  private fun takeItem(tx: DSLContext, ownerId: Long, itemId: Int, quantity: Int): Boolean {
    val match = CHARACTER_ITEMS.CHARACTER_ID.eq(ownerId).and(CHARACTER_ITEMS.ITEM_ID.eq(itemId))
    val taken =
        tx.update(CHARACTER_ITEMS)
            .set(CHARACTER_ITEMS.QUANTITY, CHARACTER_ITEMS.QUANTITY.minus(quantity))
            .where(match)
            .and(CHARACTER_ITEMS.QUANTITY.ge(quantity))
            .execute() == 1
    if (taken)
        tx.deleteFrom(CHARACTER_ITEMS).where(match).and(CHARACTER_ITEMS.QUANTITY.le(0)).execute()
    return taken
  }

  private fun partyCount(tx: DSLContext, ownerId: Long): Int =
      tx.fetchCount(
          POKEMON,
          POKEMON.OWNER_ID.eq(ownerId).and(POKEMON.CONTAINER.eq(PokemonContainer.PARTY.name)))

  /** The first free slot of a party, one past the highest otherwise. */
  private fun nextSlot(tx: DSLContext, ownerId: Long, container: PokemonContainer): Short {
    val used =
        tx.select(POKEMON.CONTAINER_SLOT)
            .from(POKEMON)
            .where(POKEMON.OWNER_ID.eq(ownerId))
            .and(POKEMON.CONTAINER.eq(container.name))
            .fetch(POKEMON.CONTAINER_SLOT)
            .filterNotNull()
            .map { it.toInt() }
            .toSet()
    if (container == PokemonContainer.PARTY) {
      return (0 until MAX_PARTY_SIZE).first { it !in used }.toShort()
    }
    return ((used.maxOrNull() ?: -1) + 1).toShort()
  }

  /** Party slots stay 0..n-1, which is what the rest of the server assumes when it appends. */
  private fun compactParty(tx: DSLContext, ownerId: Long) {
    val party =
        tx.select(POKEMON.ID, POKEMON.CONTAINER_SLOT)
            .from(POKEMON)
            .where(POKEMON.OWNER_ID.eq(ownerId))
            .and(POKEMON.CONTAINER.eq(PokemonContainer.PARTY.name))
            .orderBy(POKEMON.CONTAINER_SLOT)
            .fetch()
    party.forEachIndexed { index, rec ->
      if (rec.value2()?.toInt() != index) {
        tx.update(POKEMON)
            .set(POKEMON.CONTAINER_SLOT, index.toShort())
            .where(POKEMON.ID.eq(rec.value1()))
            .execute()
      }
    }
  }

  private fun insertListing(
      tx: DSLContext,
      sellerId: Long,
      kind: MarketKind,
      pokemonId: Long?,
      dexId: Int?,
      itemId: Int?,
      quantity: Int,
      price: Int,
      now: LocalDateTime,
      expiresAt: LocalDateTime,
      fee: Int = 0,
  ): MarketListing {
    val id =
        tx.insertInto(MARKET_LISTINGS)
            .set(MARKET_LISTINGS.SELLER_ID, sellerId)
            .set(MARKET_LISTINGS.KIND, kind.name)
            .set(MARKET_LISTINGS.POKEMON_ID, pokemonId)
            .set(MARKET_LISTINGS.DEX_ID, dexId)
            .set(MARKET_LISTINGS.ITEM_ID, itemId)
            .set(MARKET_LISTINGS.QUANTITY, quantity)
            .set(MARKET_LISTINGS.PRICE, price)
            .set(MARKET_LISTINGS.CREATED_AT, now)
            .set(MARKET_LISTINGS.EXPIRES_AT, expiresAt)
            .set(MARKET_LISTINGS.STATUS, MarketStatus.ACTIVE.name)
            .set(MARKET_LISTINGS.FEE, fee)
            .returning(MARKET_LISTINGS.ID)
            .fetchOne()!!
            .id!!
    return MarketListing(
        id,
        sellerId,
        kind,
        pokemonId,
        dexId,
        itemId,
        quantity,
        price,
        now,
        expiresAt,
        MarketStatus.ACTIVE,
        null,
        null,
        fee)
  }

  private fun MarketListingsRecord.toListing(): MarketListing =
      MarketListing(
          id = id!!,
          sellerId = sellerId,
          kind = MarketKind.valueOf(kind),
          pokemonId = pokemonId,
          dexId = dexId,
          itemId = itemId,
          quantity = quantity,
          price = price,
          createdAt = createdAt,
          expiresAt = expiresAt,
          status = MarketStatus.valueOf(status),
          buyerId = buyerId,
          soldAt = soldAt,
          fee = fee ?: 0,
      )

  private fun PokemonRecord.toPokemon(): Pokemon =
      Pokemon(
          id = id,
          ownerId = ownerId,
          container = PokemonContainer.valueOf(container),
          containerSlot = containerSlot,
          dexId = dexId,
          seed = seed,
          ot = ot,
          nickname = nickname ?: "",
          level = pokemonLevel.toByte(),
          hp = hp,
          xp = xp,
          eVs =
              EVs().also {
                it.hp = (evHp ?: 0).toInt()
                it.atk = (evAtk ?: 0).toInt()
                it.def = (evDef ?: 0).toInt()
                it.spAtk = (evSpAtk ?: 0).toInt()
                it.spDef = (evSpDef ?: 0).toInt()
                it.spd = (evSpd ?: 0).toInt()
              },
          iVs =
              IVs().also {
                it.hp = (ivHp ?: 0).toInt()
                it.atk = (ivAtk ?: 0).toInt()
                it.def = (ivDef ?: 0).toInt()
                it.spAtk = (ivSpAtk ?: 0).toInt()
                it.spDef = (ivSpDef ?: 0).toInt()
                it.spd = (ivSpd ?: 0).toInt()
              },
          moves =
              listOf(
                  PokemonMove(move1Id ?: 0, (move1Pp ?: 0).toByte()),
                  PokemonMove(move2Id ?: 0, (move2Pp ?: 0).toByte()),
                  PokemonMove(move3Id ?: 0, (move3Pp ?: 0).toByte()),
                  PokemonMove(move4Id ?: 0, (move4Pp ?: 0).toByte()),
              ),
          isShiny = isShiny ?: false,
          hasHiddenAbility = hasHiddenAbility ?: false,
          isAlpha = isAlpha ?: false,
          isSecret = isSecret ?: false,
          isFatefulEncounter = isFatefulEncounter ?: false,
          isRaidEncounter = isRaidEncounter ?: false,
          isEgg = isEgg ?: false,
          status = status ?: 0,
          friendship = (friendship ?: 70).toInt(),
          heldItem = heldItem ?: 0,
          caughtAt = caughtAt,
      )
}
