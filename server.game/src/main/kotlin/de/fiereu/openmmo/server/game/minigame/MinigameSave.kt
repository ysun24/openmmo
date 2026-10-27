package de.fiereu.openmmo.server.game.minigame

import de.fiereu.network.SessionContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** MAX_COINS (include/constants/coins.h, both games). */
const val MAX_COINS = 9999

/**
 * This lane's per-character save data (minigame_players): the coin case and a JSON object of named
 * sections ("fame", "easychat", "records", "tickets", ...). Cached per character, written through
 * on every change.
 */
@Singleton
class MinigameSave @Inject constructor(private val repo: MinigameRepository) {
  private val cache = ConcurrentHashMap<Long, MinigamePlayerRow>()
  private val locks = ConcurrentHashMap<Long, Mutex>()

  private fun lock(charId: Long) = locks.computeIfAbsent(charId) { Mutex() }

  suspend fun get(charId: Long): MinigamePlayerRow =
      cache[charId]
          ?: (repo.player(charId) ?: MinigamePlayerRow(charId)).also { cache[charId] = it }

  /** Atomically changes [charId]'s row; [change] returns the new row or null to leave it. */
  suspend fun update(
      charId: Long,
      change: (MinigamePlayerRow) -> MinigamePlayerRow?
  ): MinigamePlayerRow? =
      lock(charId).withLock {
        val now = get(charId)
        val next = change(now) ?: return@withLock null
        repo.savePlayer(next)
        cache[charId] = next
        next
      }

  suspend fun coins(charId: Long): Int = get(charId).coins

  /**
   * Adds (or with a negative [delta] removes) coins, the decomp's AddCoins / RemoveCoins: false and
   * no change when the result would leave 0..MAX_COINS.
   */
  suspend fun addCoins(charId: Long, delta: Int): Boolean =
      update(charId) { r ->
        val n = r.coins + delta
        if (n < 0 || n > MAX_COINS) null else r.copy(coins = n)
      } != null

  /** Sets coins, clamped to 0..MAX_COINS (SetCoins). */
  suspend fun setCoins(charId: Long, value: Int) {
    update(charId) { it.copy(coins = value.coerceIn(0, MAX_COINS)) }
  }

  suspend fun section(charId: Long, name: String): JsonObject =
      (get(charId).data[name] as? JsonObject) ?: JsonObject(emptyMap())

  suspend fun setSection(charId: Long, name: String, value: JsonElement) {
    update(charId) { it.copy(data = JsonObject(it.data + (name to value))) }
  }

  /** Unloads the cached row (the character left). */
  fun evict(charId: Long) {
    cache.remove(charId)
    locks.remove(charId)
  }
}

/** The coin case's state (topic "coins"), pushed whenever it changes. */
fun sendCoins(session: SessionContext, coins: Int, hasCase: Boolean) =
    MinigameChannel.send(session, "coins", jsonOf("coins" to coins, "hasCase" to hasCase))
