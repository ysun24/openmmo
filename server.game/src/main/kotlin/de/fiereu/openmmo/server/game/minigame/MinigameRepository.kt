package de.fiereu.openmmo.server.game.minigame

import de.fiereu.openmmo.db.game.tables.references.MINIGAME_PLAYERS
import de.fiereu.openmmo.db.game.tables.references.MIXED_RECORDS
import de.fiereu.openmmo.db.game.tables.references.MYSTERY_GIFTS
import de.fiereu.openmmo.db.game.tables.references.MYSTERY_GIFT_CLAIMS
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jooq.DSLContext

/** A character's row of V10_4 minigame_players. */
data class MinigamePlayerRow(
    val characterId: Long,
    val coins: Int = 0,
    val data: JsonObject = JsonObject(emptyMap()),
)

/** An admin-defined Mystery Gift (mystery_gifts). */
data class MysteryGiftRow(
    val id: Int,
    val kind: String,
    /** 0 = both games, 1 = Kanto, 2 = Hoenn. */
    val region: Int,
    val title: String,
    val body: String,
    val payload: JsonObject,
    val active: Boolean = true,
    val createdBy: String = "",
)

data class MixedRecordRow(
    val id: Long,
    val characterId: Long,
    val kind: String,
    val payload: JsonObject,
    val createdAt: LocalDateTime,
)

/** Persistence of this lane (V10_4__minigames). */
interface MinigameRepository {
  suspend fun player(charId: Long): MinigamePlayerRow?

  suspend fun savePlayer(row: MinigamePlayerRow)

  suspend fun gifts(): List<MysteryGiftRow>

  suspend fun addGift(row: MysteryGiftRow): Int

  suspend fun setGiftActive(id: Int, active: Boolean)

  /** gift id -> claim state (0 received, 1 delivered) for [charId]. */
  suspend fun claims(charId: Long): Map<Int, Int>

  suspend fun setClaim(giftId: Int, charId: Long, state: Int)

  suspend fun addRecord(charId: Long, kind: String, payload: JsonObject)

  /** The newest [limit] records of [kind] from characters other than [exceptCharId]. */
  suspend fun records(kind: String, exceptCharId: Long, limit: Int): List<MixedRecordRow>
}

private fun parse(s: String?): JsonObject =
    runCatching { Json.parseToJsonElement(s ?: "{}") as JsonObject }
        .getOrDefault(JsonObject(emptyMap()))

@Singleton
class JooqMinigameRepository
@Inject
constructor(
    private val dsl: DSLContext,
    @param:Named("db") private val dispatcher: CoroutineDispatcher,
) : MinigameRepository {
  private suspend fun <T> db(block: (DSLContext) -> T): T = withContext(dispatcher) { block(dsl) }

  override suspend fun player(charId: Long): MinigamePlayerRow? = db { d ->
    d.selectFrom(MINIGAME_PLAYERS).where(MINIGAME_PLAYERS.CHARACTER_ID.eq(charId)).fetchOne()?.let {
      MinigamePlayerRow(charId, it.coins ?: 0, parse(it.data))
    }
  }

  override suspend fun savePlayer(row: MinigamePlayerRow) {
    val now = LocalDateTime.now()
    db { d ->
      d.insertInto(MINIGAME_PLAYERS)
          .set(MINIGAME_PLAYERS.CHARACTER_ID, row.characterId)
          .set(MINIGAME_PLAYERS.COINS, row.coins)
          .set(MINIGAME_PLAYERS.DATA, row.data.toString())
          .set(MINIGAME_PLAYERS.UPDATED_AT, now)
          .onConflict(MINIGAME_PLAYERS.CHARACTER_ID)
          .doUpdate()
          .set(MINIGAME_PLAYERS.COINS, row.coins)
          .set(MINIGAME_PLAYERS.DATA, row.data.toString())
          .set(MINIGAME_PLAYERS.UPDATED_AT, now)
          .execute()
    }
  }

  override suspend fun gifts(): List<MysteryGiftRow> = db { d ->
    d.selectFrom(MYSTERY_GIFTS).orderBy(MYSTERY_GIFTS.ID).fetch {
      MysteryGiftRow(
          it.id!!,
          it.kind!!,
          it.region?.toInt() ?: 0,
          it.title!!,
          it.body ?: "",
          parse(it.payload),
          it.active ?: true,
          it.createdBy ?: "")
    }
  }

  override suspend fun addGift(row: MysteryGiftRow): Int = db { d ->
    d.insertInto(MYSTERY_GIFTS)
        .set(MYSTERY_GIFTS.KIND, row.kind)
        .set(MYSTERY_GIFTS.REGION, row.region.toShort())
        .set(MYSTERY_GIFTS.TITLE, row.title)
        .set(MYSTERY_GIFTS.BODY, row.body)
        .set(MYSTERY_GIFTS.PAYLOAD, row.payload.toString())
        .set(MYSTERY_GIFTS.ACTIVE, row.active)
        .set(MYSTERY_GIFTS.CREATED_BY, row.createdBy)
        .set(MYSTERY_GIFTS.CREATED_AT, LocalDateTime.now())
        .returning(MYSTERY_GIFTS.ID)
        .fetchOne()!!
        .id!!
  }

  override suspend fun setGiftActive(id: Int, active: Boolean) {
    db { d ->
      d.update(MYSTERY_GIFTS)
          .set(MYSTERY_GIFTS.ACTIVE, active)
          .where(MYSTERY_GIFTS.ID.eq(id))
          .execute()
    }
  }

  override suspend fun claims(charId: Long): Map<Int, Int> = db { d ->
    d.selectFrom(MYSTERY_GIFT_CLAIMS)
        .where(MYSTERY_GIFT_CLAIMS.CHARACTER_ID.eq(charId))
        .fetch()
        .associate { it.giftId!! to (it.state?.toInt() ?: 0) }
  }

  override suspend fun setClaim(giftId: Int, charId: Long, state: Int) {
    db { d ->
      d.insertInto(MYSTERY_GIFT_CLAIMS)
          .set(MYSTERY_GIFT_CLAIMS.GIFT_ID, giftId)
          .set(MYSTERY_GIFT_CLAIMS.CHARACTER_ID, charId)
          .set(MYSTERY_GIFT_CLAIMS.STATE, state.toShort())
          .set(MYSTERY_GIFT_CLAIMS.CLAIMED_AT, LocalDateTime.now())
          .onConflict(MYSTERY_GIFT_CLAIMS.GIFT_ID, MYSTERY_GIFT_CLAIMS.CHARACTER_ID)
          .doUpdate()
          .set(MYSTERY_GIFT_CLAIMS.STATE, state.toShort())
          .execute()
    }
  }

  override suspend fun addRecord(charId: Long, kind: String, payload: JsonObject) {
    db { d ->
      d.insertInto(MIXED_RECORDS)
          .set(MIXED_RECORDS.CHARACTER_ID, charId)
          .set(MIXED_RECORDS.KIND, kind)
          .set(MIXED_RECORDS.PAYLOAD, payload.toString())
          .set(MIXED_RECORDS.CREATED_AT, LocalDateTime.now())
          .execute()
    }
  }

  override suspend fun records(kind: String, exceptCharId: Long, limit: Int) = db { d ->
    d.selectFrom(MIXED_RECORDS)
        .where(MIXED_RECORDS.KIND.eq(kind))
        .and(MIXED_RECORDS.CHARACTER_ID.ne(exceptCharId))
        .orderBy(MIXED_RECORDS.CREATED_AT.desc())
        .limit(limit)
        .fetch {
          MixedRecordRow(it.id!!, it.characterId!!, it.kind!!, parse(it.payload), it.createdAt!!)
        }
  }
}

/** For tests and runs without a database. */
class InMemoryMinigameRepository : MinigameRepository {
  val players = ConcurrentHashMap<Long, MinigamePlayerRow>()
  val giftRows = ConcurrentHashMap<Int, MysteryGiftRow>()
  private val claimRows = ConcurrentHashMap<Pair<Int, Long>, Int>()
  private val recordRows = java.util.concurrent.CopyOnWriteArrayList<MixedRecordRow>()
  private val ids = AtomicInteger()
  private val recordIds = AtomicLong()

  override suspend fun player(charId: Long) = players[charId]

  override suspend fun savePlayer(row: MinigamePlayerRow) {
    players[row.characterId] = row
  }

  override suspend fun gifts() = giftRows.values.sortedBy { it.id }

  override suspend fun addGift(row: MysteryGiftRow): Int {
    val id = ids.incrementAndGet()
    giftRows[id] = row.copy(id = id)
    return id
  }

  override suspend fun setGiftActive(id: Int, active: Boolean) {
    giftRows.computeIfPresent(id) { _, g -> g.copy(active = active) }
  }

  override suspend fun claims(charId: Long) =
      claimRows.filterKeys { it.second == charId }.mapKeys { it.key.first }

  override suspend fun setClaim(giftId: Int, charId: Long, state: Int) {
    claimRows[giftId to charId] = state
  }

  override suspend fun addRecord(charId: Long, kind: String, payload: JsonObject) {
    recordRows +=
        MixedRecordRow(recordIds.incrementAndGet(), charId, kind, payload, LocalDateTime.now())
  }

  override suspend fun records(kind: String, exceptCharId: Long, limit: Int) =
      recordRows
          .filter { it.kind == kind && it.characterId != exceptCharId }
          .sortedByDescending { it.id }
          .take(limit)
}
