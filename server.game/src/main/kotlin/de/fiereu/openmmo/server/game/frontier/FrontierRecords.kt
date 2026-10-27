package de.fiereu.openmmo.server.game.frontier

import de.fiereu.openmmo.db.game.tables.references.FRONTIER_RECORDS
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.jooq.DSLContext

/** One streak record: current streak, the best one, and whether the current one is still alive. */
data class Streak(var current: Int = 0, var best: Int = 0, var active: Boolean = false)

/**
 * The frontier save (SaveBlock2 frontier). Streak keys are "facility:battlemode:levelmode" (e.g.
 * "tower:singles:50", "pike:singles:open"), tents "tent:slateport".
 */
class FrontierSave(
    var battlePoints: Int = 0,
    /** cardBattlePoints: every BP ever earned (the Frontier Pass). */
    var earnedPoints: Int = 0,
    /** Facility key -> symbols: 0 none, 1 Silver, 2 Gold. */
    val symbols: MutableMap<String, Int> = mutableMapOf(),
    /** Facility keys of brains battled (sBattledBrainBitFlags). */
    val brainsBattled: MutableSet<String> = mutableSetOf(),
    val streaks: MutableMap<String, Streak> = mutableMapOf(),
    /** factoryRentsCount per "battlemode:levelmode". */
    val factoryRents: MutableMap<String, Int> = mutableMapOf(),
    /** Trainer Hill best times per mode, Trainer Tower per challenge type, in milliseconds. */
    val hillBest: MutableMap<String, Long> = mutableMapOf(),
    val towerBest: MutableMap<String, Long> = mutableMapOf(),
    /** Prizes taken once per mode / type (receivedPrize). */
    val prizesTaken: MutableSet<String> = mutableSetOf(),
) {
  fun streak(key: String): Streak = streaks.getOrPut(key) { Streak() }

  fun symbolsOf(f: Facility): Int = symbols[f.key] ?: 0

  fun addPoints(n: Int) {
    battlePoints = (battlePoints + n).coerceAtMost(FrontierRules.MAX_BATTLE_FRONTIER_POINTS)
    earnedPoints = (earnedPoints + n).coerceAtMost(0xFFFF)
  }

  fun toJson(): JsonObject = buildJsonObject {
    put("bp", battlePoints)
    put("earned", earnedPoints)
    put("symbols", JsonObject(symbols.mapValues { JsonPrimitive(it.value) }))
    put("brains", JsonArray(brainsBattled.map { JsonPrimitive(it) }))
    put(
        "streaks",
        JsonObject(
            streaks.mapValues { (_, s) ->
              buildJsonObject {
                put("cur", s.current)
                put("best", s.best)
                put("active", s.active)
              }
            }))
    put("rents", JsonObject(factoryRents.mapValues { JsonPrimitive(it.value) }))
    put("hill", JsonObject(hillBest.mapValues { JsonPrimitive(it.value) }))
    put("ttower", JsonObject(towerBest.mapValues { JsonPrimitive(it.value) }))
    put("prizes", JsonArray(prizesTaken.map { JsonPrimitive(it) }))
  }

  companion object {
    fun fromJson(bp: Int, text: String): FrontierSave {
      val o =
          runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
              ?: JsonObject(emptyMap())
      fun obj(k: String) = o[k]?.jsonObject ?: JsonObject(emptyMap())
      return FrontierSave(
          battlePoints = bp,
          earnedPoints = o["earned"]?.jsonPrimitive?.int ?: 0,
          symbols = obj("symbols").mapValues { it.value.jsonPrimitive.int }.toMutableMap(),
          brainsBattled =
              o["brains"]?.jsonArray?.map { it.jsonPrimitive.content }?.toMutableSet()
                  ?: mutableSetOf(),
          streaks =
              obj("streaks")
                  .mapValues { (_, v) ->
                    val s = v.jsonObject
                    Streak(
                        s["cur"]?.jsonPrimitive?.int ?: 0,
                        s["best"]?.jsonPrimitive?.int ?: 0,
                        s["active"]?.jsonPrimitive?.boolean ?: false)
                  }
                  .toMutableMap(),
          factoryRents = obj("rents").mapValues { it.value.jsonPrimitive.int }.toMutableMap(),
          hillBest = obj("hill").mapValues { it.value.jsonPrimitive.long }.toMutableMap(),
          towerBest = obj("ttower").mapValues { it.value.jsonPrimitive.long }.toMutableMap(),
          prizesTaken =
              o["prizes"]?.jsonArray?.map { it.jsonPrimitive.content }?.toMutableSet()
                  ?: mutableSetOf(),
      )
    }
  }
}

/** Persistence of [FrontierSave] (V10_3__frontier_records). */
interface FrontierRepository {
  suspend fun load(charId: Long): FrontierSave

  suspend fun save(charId: Long, save: FrontierSave)
}

@Singleton
class JooqFrontierRepository
@Inject
constructor(
    private val dsl: DSLContext,
    @param:Named("db") private val dispatcher: CoroutineDispatcher,
) : FrontierRepository {
  private suspend fun <T> db(block: (DSLContext) -> T): T = withContext(dispatcher) { block(dsl) }

  override suspend fun load(charId: Long): FrontierSave = db { d ->
    val r =
        d.select(FRONTIER_RECORDS.BATTLE_POINTS, FRONTIER_RECORDS.DATA)
            .from(FRONTIER_RECORDS)
            .where(FRONTIER_RECORDS.CHARACTER_ID.eq(charId))
            .fetchOne()
    if (r == null) FrontierSave()
    else
        FrontierSave.fromJson(
            r.get(FRONTIER_RECORDS.BATTLE_POINTS) ?: 0, r.get(FRONTIER_RECORDS.DATA) ?: "{}")
  }

  override suspend fun save(charId: Long, save: FrontierSave) {
    val now = LocalDateTime.now()
    val json = save.toJson().toString()
    db { d ->
      d.insertInto(FRONTIER_RECORDS)
          .set(FRONTIER_RECORDS.CHARACTER_ID, charId)
          .set(FRONTIER_RECORDS.BATTLE_POINTS, save.battlePoints)
          .set(FRONTIER_RECORDS.DATA, json)
          .set(FRONTIER_RECORDS.UPDATED_AT, now)
          .onConflict(FRONTIER_RECORDS.CHARACTER_ID)
          .doUpdate()
          .set(FRONTIER_RECORDS.BATTLE_POINTS, save.battlePoints)
          .set(FRONTIER_RECORDS.DATA, json)
          .set(FRONTIER_RECORDS.UPDATED_AT, now)
          .execute()
    }
  }
}

/** For tests. */
class InMemoryFrontierRepository : FrontierRepository {
  val rows = ConcurrentHashMap<Long, String>()
  val bp = ConcurrentHashMap<Long, Int>()

  override suspend fun load(charId: Long): FrontierSave =
      FrontierSave.fromJson(bp[charId] ?: 0, rows[charId] ?: "{}")

  override suspend fun save(charId: Long, save: FrontierSave) {
    rows[charId] = save.toJson().toString()
    bp[charId] = save.battlePoints
  }
}
