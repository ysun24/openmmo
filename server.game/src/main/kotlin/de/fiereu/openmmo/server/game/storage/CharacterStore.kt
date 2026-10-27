package de.fiereu.openmmo.server.game.storage

import de.fiereu.openmmo.common.CharacterInfo
import de.fiereu.openmmo.common.DynamicWarp
import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.Skin
import de.fiereu.openmmo.common.enums.CharacterGender
import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.common.enums.SkinSlot
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val log = KotlinLogging.logger {}

private val FLUSH_TICK = 5.seconds
private val FLUSH_DEBOUNCE = 10.seconds

// Above any real party or PC slot, where rearrangeMonsters parks monsters mid-move.
private const val PARKING_SLOT = 30000

data class StoredCharacter(
    val info: CharacterInfo,
    val pokemon: MutableList<Pokemon>,
    val pcStorage: MutableList<Pokemon>,
    val items: MutableMap<Int, Int>,
    // Story progression. Flags are set/unset booleans, vars are named integers that default to 0.
    // Keys are opaque strings supplied by the content layer, so the store stays game agnostic.
    val storyFlags: MutableSet<String> = mutableSetOf(),
    val storyVars: MutableMap<String, Int> = mutableMapOf(),
    val skins: Map<SkinSlot, Skin> = emptyMap(),
)

/**
 * Write-through cache over [CharacterRepository]. Memory is the live version and the database
 * mirrors it. Aggregates enter the database on creation and are written back when marked dirty:
 * after a debounce by the periodic flusher, or immediately through [flushCharacterAsync] on events
 * like warps. A disconnect goes through [unloadCharacterAsync], which also evicts the aggregate
 * from the cache once its last write succeeded, so only connected players stay in memory.
 */
@Singleton
class CharacterStore
@Inject
constructor(
    private val repository: CharacterRepository,
    private val entityIds: EntityIdService,
    scope: CoroutineScope,
) {
  private val flushJob = SupervisorJob()
  private val flushScope = CoroutineScope(scope.coroutineContext + flushJob)
  private var periodicJob: Job? = null

  private val characters = ConcurrentHashMap<Long, StoredCharacter>()
  private val charactersByUser = ConcurrentHashMap<Int, CopyOnWriteArrayList<Long>>()
  // The last aggregate a write succeeded for. A flush sends the difference to it.
  private val persisted = ConcurrentHashMap<Long, StoredCharacter>()
  private val dirtySince = ConcurrentHashMap<Long, Long>()
  private val pendingUnload = ConcurrentHashMap.newKeySet<Long>()
  private val flushLocks = ConcurrentHashMap<Long, Mutex>()

  /** Create a character with its own entity id and an empty party. */
  suspend fun createCharacter(
      userId: Int,
      name: String,
      gender: CharacterGender,
      startingRegion: Region,
      skins: Map<SkinSlot, Skin> = emptyMap(),
      skinRegionSelectionIndex: Int = 0,
  ): StoredCharacter {
    val female = gender == CharacterGender.FEMALE
    val start = NewGameStarts.forRegion(startingRegion, female)
    val id = entityIds.newCharacterId()
    val now = LocalDateTime.now()
    val info =
        CharacterInfo(
            id = id,
            name = name,
            namePrefix = "",
            userId = userId,
            // This historical field stores the player's gender.
            rivalSex = gender.wireValue,
            skinRegionSelectionIndex = skinRegionSelectionIndex,
            lastLogin = now,
            createdAt = now,
            money = 30000,
            permissions = 8,
            remainingSafariSteps = 0,
            remainingSafariBalls = 0,
            pcExtraSlots = 0,
            battleBoxExtraSlots = 0,
            templateAmount = 0,
            positionRegionId = startingRegion.wireValue,
            positionBankId = start.bankId,
            positionMapId = start.mapId,
            positionX = start.x,
            positionY = start.y,
            repelLeft = 0,
            repelItemId = 0,
            lureLeft = 0,
            lureItemId = 0,
            dynamicWarp = start.dynamicWarp,
        )
    val stored =
        StoredCharacter(
            info,
            mutableListOf(),
            mutableListOf(),
            mutableMapOf(),
            storyFlags = start.storyFlags.toMutableSet(),
            storyVars = start.storyVars.toMutableMap(),
            skins = skins.toMap(),
        )
    repository.insertAggregate(stored)
    characters[id] = stored
    persisted[id] = stored
    charactersByUser.computeIfAbsent(userId) { CopyOnWriteArrayList() }.add(id)
    return stored
  }

  fun getCharacter(id: Long): StoredCharacter? = characters[id]

  /** Like [getCharacter] but falls back to the database when the cache has no entry. */
  suspend fun getOrLoadCharacter(id: Long): StoredCharacter? {
    pendingUnload.remove(id)
    characters[id]?.let {
      return it
    }
    // Under the character's lock, so a [withExternalWrite] committing to this character cannot
    // land between the read and the cache insert and leave a stale copy behind.
    val loaded = lockFor(id).withLock { characters[id] ?: repository.loadById(id) } ?: return null
    return cache(loaded)
  }

  /**
   * For changes another component commits straight to the database, such as a market sale that
   * moves money and goods between two characters in one transaction.
   *
   * Every listed character that is cached is locked (in id order, so two callers cannot deadlock)
   * and flushed first, so the database is current when [block] reads it. After [block] returns the
   * money, monsters and bag of those characters are re-read from the database into both the live
   * copy and the last-persisted snapshot, so the next flush does not write the old values back.
   * Characters that are not cached are only in the database and need nothing else.
   */
  suspend fun <T> withExternalWrite(characterIds: Collection<Long>, block: suspend () -> T): T {
    val ids = characterIds.distinct().sorted()
    val locks = ids.map { lockFor(it) }
    locks.forEach { it.lock() }
    try {
      for (id in ids) {
        if (characters.containsKey(id) && !flushLocked(id, allowEvict = false)) {
          throw IllegalStateException("Character $id could not be flushed")
        }
      }
      val result = block()
      for (id in ids) reloadTradeables(id)
      return result
    } finally {
      locks.asReversed().forEach { it.unlock() }
    }
  }

  private suspend fun reloadTradeables(id: Long) {
    if (!characters.containsKey(id)) return
    val fresh = repository.loadById(id) ?: return
    val refresh = { stored: StoredCharacter ->
      stored.copy(
          info = stored.info.copy(money = fresh.info.money),
          pokemon = fresh.pokemon.toMutableList(),
          pcStorage = fresh.pcStorage.toMutableList(),
          items = fresh.items.toMutableMap(),
      )
    }
    characters.computeIfPresent(id) { _, stored -> refresh(stored) }
    persisted.computeIfPresent(id) { _, stored -> refresh(stored) }
  }

  suspend fun getCharactersByUser(userId: Int): List<StoredCharacter> {
    val cachedIds: List<Long>? = charactersByUser[userId]
    if (cachedIds != null) {
      cachedIds.forEach { pendingUnload.remove(it) }
      return cachedIds.mapNotNull { characters[it] }
    }
    val loaded = repository.loadByUser(userId).map { cache(it) }
    loaded.forEach { pendingUnload.remove(it.info.id) }
    charactersByUser.putIfAbsent(userId, CopyOnWriteArrayList(loaded.map { it.info.id }))
    return loaded
  }

  /** Permanently delete an owned character and evict every cached reference to it. */
  suspend fun deleteCharacter(userId: Int, characterId: Long): Boolean {
    if (!repository.deleteById(userId, characterId)) return false
    characters.remove(characterId)
    persisted.remove(characterId)
    charactersByUser[userId]?.remove(characterId)
    dirtySince.remove(characterId)
    pendingUnload.remove(characterId)
    return true
  }

  /**
   * Applies [change] to the cached character under the map's own lock. Scripts run on their own
   * coroutine while packets are answered on the mailbox coroutine, so a plain read, copy and write
   * would let one thread drop the other's field.
   */
  private fun mutate(characterId: Long, change: (StoredCharacter) -> StoredCharacter?): Boolean {
    var applied = false
    val present =
        characters.computeIfPresent(characterId) { _, stored ->
          val updated = change(stored)
          if (updated == null) {
            stored
          } else {
            applied = true
            updated
          }
        }
    if (present == null) {
      // A disconnect evicts the character, so a script finishing its last statements reaches this.
      log.warn { "Dropped a write for character $characterId, it is no longer cached" }
      return false
    }
    if (applied) markDirty(characterId)
    return applied
  }

  /** Set the steps a repel has left, and which item it was. */
  fun setRepel(characterId: Long, steps: Int, itemId: Int) {
    mutate(characterId) {
      it.copy(info = it.info.copy(repelLeft = steps.toShort(), repelItemId = itemId.toShort()))
    }
  }

  fun updateCharacter(info: CharacterInfo) {
    mutate(info.id) { it.copy(info = info) }
  }

  fun updatePosition(
      characterId: Long,
      x: Short,
      y: Short,
      bankId: Byte? = null,
      mapId: Byte? = null,
      facing: Direction? = null,
  ) {
    mutate(characterId) { stored ->
      stored.copy(
          info =
              stored.info.copy(
                  positionX = x,
                  positionY = y,
                  positionBankId = bankId ?: stored.info.positionBankId,
                  positionMapId = mapId ?: stored.info.positionMapId,
                  positionFacing = facing ?: stored.info.positionFacing,
              ),
      )
    }
  }

  /** False when the monster could not be written, in which case the party is left as it was. */
  suspend fun addPokemon(characterId: Long, pokemon: Pokemon): Boolean =
      mutateDurably(
          characterId,
          // Copy instead of mutating in place, so flusher snapshots never see a half-updated list.
          apply = { it.copy(pokemon = (it.pokemon + pokemon).toMutableList()) },
          rollback = {
            it.copy(pokemon = it.pokemon.filter { m -> m.id != pokemon.id }.toMutableList())
          },
      )

  /** Replace one party monster by id, for example after a battle changed hp, xp, or level. */
  fun updatePokemon(characterId: Long, updated: Pokemon) {
    mutate(characterId) { stored ->
      stored.copy(
          pokemon = stored.pokemon.map { if (it.id == updated.id) updated else it }.toMutableList())
    }
  }

  /**
   * Replace the party and PC with [party] and [pc], the same monsters in new places (a party
   * reorder, a deposit, a withdrawal). False when nothing was written, which leaves both as they
   * were. The slot unique key is deferred, so two monsters can trade slots in one write.
   */
  suspend fun rearrangeMonsters(
      characterId: Long,
      party: List<Pokemon>,
      pc: List<Pokemon>,
  ): Boolean {
    val before = characters[characterId] ?: return false
    val was = (before.pokemon + before.pcStorage).associateBy { it.id }
    val moved =
        (party + pc).filter { m ->
          was[m.id]?.let { it.container != m.container || it.containerSlot != m.containerSlot }
              ?: true
        }
    if (moved.isEmpty()) return true
    // The write is a merge that also matches on (owner, container, slot), so a monster moving into
    // a slot another one is leaving would update that other row. Parking every moved monster on a
    // free slot first leaves each final slot matched by id alone.
    val parkedIds = moved.mapIndexed { i, m -> m.id to (PARKING_SLOT + i).toShort() }.toMap()
    fun park(m: Pokemon) = parkedIds[m.id]?.let { m.copy(containerSlot = it) } ?: m
    val parked =
        before.copy(
            pokemon = party.map(::park).toMutableList(), pcStorage = pc.map(::park).toMutableList())
    if (!mutateDurably(characterId, apply = { parked }, rollback = { before })) return false
    if (mutateDurably(
        characterId,
        apply = { it.copy(pokemon = party.toMutableList(), pcStorage = pc.toMutableList()) },
        rollback = { parked },
    )) {
      return true
    }
    // Put everyone back where they were; from parked slots that cannot collide either.
    mutateDurably(characterId, apply = { before }, rollback = { parked })
    return false
  }

  /** False when the change could not be written, in which case the balance is left as it was. */
  suspend fun addMoney(characterId: Long, amount: Int): Boolean =
      mutateDurably(
          characterId,
          apply = { it.copy(info = it.info.copy(money = it.info.money + amount)) },
          // Undo the delta rather than restoring a snapshot, so a concurrent edit to another field
          // of the same character survives.
          rollback = { it.copy(info = it.info.copy(money = it.info.money - amount)) },
      )

  /** Add (or remove with a negative amount) one persisted bag stack. */
  /** False when the bag would go negative, or when the change could not be written. */
  suspend fun addItem(characterId: Long, itemId: Int, amount: Int): Boolean =
      mutateDurably(
          characterId,
          apply = { stored ->
            val newQuantity = (stored.items[itemId] ?: 0) + amount
            if (newQuantity < 0) return@mutateDurably null
            val items = stored.items.toMutableMap()
            if (newQuantity == 0) items.remove(itemId) else items[itemId] = newQuantity
            stored.copy(items = items)
          },
          rollback = { stored ->
            val reverted = (stored.items[itemId] ?: 0) - amount
            val items = stored.items.toMutableMap()
            if (reverted <= 0) items.remove(itemId) else items[itemId] = reverted
            stored.copy(items = items)
          },
      )

  /**
   * Rewrites two characters at once, both in memory and in one database transaction. [compute] sees
   * both as they are now and returns their new versions, or null to change nothing. False when
   * nothing changed: [compute] refused, a character is not cached, or the write failed.
   *
   * Both flush locks are held throughout, in id order, so no durable change to either character (a
   * purchase, a caught monster, another trade) can interleave with the swap.
   */
  suspend fun commitPair(
      aId: Long,
      bId: Long,
      compute: (StoredCharacter, StoredCharacter) -> Pair<StoredCharacter, StoredCharacter>?,
  ): Boolean {
    require(aId != bId) { "A pair needs two characters" }
    val (first, second) = if (aId < bId) aId to bId else bId to aId
    return lockFor(first).withLock {
      lockFor(second).withLock {
        val baseA = characters[aId] ?: return@withLock false
        val baseB = characters[bId] ?: return@withLock false
        val (newA, newB) = compute(baseA, baseB) ?: return@withLock false
        try {
          repository.saveTrade(listOf(persisted[aId] to newA, persisted[bId] to newB))
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          log.warn(e) { "Failed to persist the trade between $aId and $bId" }
          return@withLock false
        }
        persisted[aId] = newA
        persisted[bId] = newB
        // A non durable write (a step, a story flag) can land between the read and here. Redo the
        // swap on top of it rather than dropping it; the result differs from what was written, so
        // it is marked dirty and the next flush catches the database up.
        val recomputed =
            if (characters[aId] === baseA && characters[bId] === baseB) null
            else characters[aId]?.let { a -> characters[bId]?.let { b -> compute(a, b) } }
        install(aId, baseA, newA, recomputed?.first)
        install(bId, baseB, newB, recomputed?.second)
        true
      }
    }
  }

  private fun install(
      id: Long,
      base: StoredCharacter,
      written: StoredCharacter,
      redo: StoredCharacter?
  ) {
    var diverged = false
    characters.computeIfPresent(id) { _, current ->
      if (current === base) {
        written
      } else {
        diverged = true
        redo ?: written
      }
    }
    if (diverged) markDirty(id)
  }

  /** Set (or clear with null) the runtime destination for MAP_DYNAMIC warps (setdynamicwarp). */
  fun setDynamicWarp(characterId: Long, warp: DynamicWarp?) {
    mutate(characterId) { it.copy(info = it.info.copy(dynamicWarp = warp)) }
  }

  /** Set a story flag. Copies the set so flusher snapshots never see a half-updated collection. */
  fun setStoryFlag(characterId: Long, flag: String) {
    mutate(characterId) { stored ->
      if (flag in stored.storyFlags) null
      else stored.copy(storyFlags = (stored.storyFlags + flag).toMutableSet())
    }
  }

  fun clearStoryFlag(characterId: Long, flag: String) {
    mutate(characterId) { stored ->
      if (flag !in stored.storyFlags) null
      else stored.copy(storyFlags = (stored.storyFlags - flag).toMutableSet())
    }
  }

  /** Set a story var. A value of 0 is the default, so it drops the row instead of storing it. */
  fun setStoryVar(characterId: Long, key: String, value: Int) {
    mutate(characterId) { stored ->
      val newVars = stored.storyVars.toMutableMap()
      if (value == 0) newVars.remove(key) else newVars[key] = value
      if (newVars == stored.storyVars) null else stored.copy(storyVars = newVars)
    }
  }

  /** Replaces every monster, the party and the pc alike, along with the bag and story state. */
  fun replaceProgress(
      characterId: Long,
      party: List<Pokemon>,
      items: Map<Int, Int>,
      storyFlags: Set<String>,
      storyVars: Map<String, Int>,
  ) {
    mutate(characterId) { stored ->
      stored.copy(
          pokemon = party.toMutableList(),
          pcStorage = mutableListOf(),
          items = items.toMutableMap(),
          storyFlags = storyFlags.toMutableSet(),
          // A var of 0 is the default, so it is stored as absent everywhere else too.
          storyVars = storyVars.filterValues { it != 0 }.toMutableMap(),
      )
    }
  }

  /** Puts a character back to a [snapshot] taken earlier. */
  fun restoreProgress(characterId: Long, snapshot: StoredCharacter) {
    mutate(characterId) { snapshot }
  }

  fun startPeriodicFlush() {
    periodicJob =
        flushScope.launch {
          while (isActive) {
            delay(FLUSH_TICK)
            flushOlderThan(FLUSH_DEBOUNCE.inWholeMilliseconds)
          }
        }
  }

  /** Flush one character soon, skipping the debounce. Safe to call from Netty threads. */
  fun flushCharacterAsync(characterId: Long) {
    flushScope.launch { flush(characterId) }
  }

  /**
   * Marks the character dirty and writes it before returning. Anything a player can trade or spend
   * goes through here, so a crash cannot lose an item that the client was already told it has.
   * Position, hp and story progress do not, since replaying a few seconds of those costs nothing.
   */
  /**
   * Applies a change to something a player can trade or spend and writes it before returning. A
   * failed write is undone by [rollback] and reported, so a caller never tells a player about an
   * item, a coin or a monster the database did not accept.
   */
  private suspend fun mutateDurably(
      characterId: Long,
      apply: (StoredCharacter) -> StoredCharacter?,
      rollback: (StoredCharacter) -> StoredCharacter,
  ): Boolean =
      lockFor(characterId).withLock {
        // Through [mutate] rather than a read and a write, so a script writing another field
        // between the two is not clobbered by the one this puts back.
        if (!mutate(characterId, apply)) return@withLock false
        if (flushLocked(characterId, allowEvict = false)) return@withLock true
        mutate(characterId, rollback)
        dirtySince.remove(characterId)
        false
      }

  /**
   * Persist the character and drop it from the cache once the write succeeded. While the save keeps
   * failing the character stays cached and dirty, and the periodic flusher finishes the eviction on
   * its next successful write. Loading the character again cancels the unload.
   */
  fun unloadCharacterAsync(characterId: Long) {
    pendingUnload.add(characterId)
    flushScope.launch { flush(characterId) }
  }

  suspend fun flushAll() {
    for (id in dirtySince.keys) flush(id)
  }

  /** Stop the periodic loop, wait for in-flight flushes, then persist whatever is still dirty. */
  suspend fun shutdown() {
    periodicJob?.cancel()
    flushJob.children.toList().joinAll()
    flushAll()
  }

  private fun cache(stored: StoredCharacter): StoredCharacter {
    val existing = characters.putIfAbsent(stored.info.id, stored)
    if (existing == null) persisted[stored.info.id] = stored
    return existing ?: stored
  }

  private fun markDirty(id: Long) {
    dirtySince.putIfAbsent(id, System.currentTimeMillis())
  }

  private suspend fun flushOlderThan(ageMs: Long) {
    val now = System.currentTimeMillis()
    for ((id, since) in dirtySince) {
      if (now - since >= ageMs) flush(id)
    }
  }

  // Serialised per character. Without this a second flush takes the dirty marker, skips its own
  // write and returns while the first is still inside saveChanges, so persistNow would promise a
  // write it did not make.
  private suspend fun flush(id: Long) {
    lockFor(id).withLock { flushLocked(id, allowEvict = true) }
  }

  private fun lockFor(id: Long): Mutex = flushLocks.computeIfAbsent(id) { Mutex() }

  /** True when the character is in the database, either because it was written or was not dirty. */
  private suspend fun flushLocked(id: Long, allowEvict: Boolean): Boolean {
    val since = dirtySince.remove(id)
    val stored = characters[id]
    if (since != null && stored != null) {
      try {
        repository.saveChanges(persisted[id], stored)
        // The written instance, not the current one, so a racing mutation stays dirty.
        persisted[id] = stored
      } catch (e: CancellationException) {
        // A disconnect cancelling the caller must not read as a failed write.
        dirtySince.putIfAbsent(id, since)
        throw e
      } catch (e: Exception) {
        log.warn(e) { "Failed to persist character $id, will retry" }
        dirtySince.putIfAbsent(id, since)
        return false
      }
    }
    // A durable mutation must not evict the character its own caller is still working with.
    if (allowEvict) maybeEvict(id)
    return true
  }

  private fun maybeEvict(id: Long) {
    if (!pendingUnload.remove(id)) return
    if (dirtySince.containsKey(id)) {
      pendingUnload.add(id)
      return
    }
    val stored = characters.remove(id) ?: return
    persisted.remove(id)
    flushLocks.remove(id)
    charactersByUser.remove(stored.info.userId)
  }
}
