package de.fiereu.openmmo.server.game.contest

import de.fiereu.network.SessionContext
import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.maps.MapManager
import de.fiereu.openmmo.moves.MoveRegistry
import de.fiereu.openmmo.net.game.packets.contest.ContestStatePacket
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import de.fiereu.openmmo.server.game.battle.ItemNames
import de.fiereu.openmmo.server.game.services.NpcService
import de.fiereu.openmmo.server.game.services.storyItemStacksPacket
import de.fiereu.openmmo.server.game.session.PLAYER_STATE
import de.fiereu.openmmo.server.game.session.PlayerState
import de.fiereu.openmmo.server.game.storage.CharacterStore
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private val log = KotlinLogging.logger {}

/** Random() for the live services. */
object LiveRng : ContestRng {
  override fun next(): Int = Random.nextInt(0, 0x10000)
}

/** Sends r5-contests documents (s2c 0xC6) and waits for UI answers (c2s 0xD5). */
@Singleton
class ContestDocs @Inject constructor() {
  private val waits = ConcurrentHashMap<Pair<SessionContext, String>, CompletableDeferred<JsonObject>>()

  fun send(session: SessionContext, kind: String, body: JsonObjectBuilder.() -> Unit) {
    val doc = buildJsonObject {
      put("k", kind)
      body()
    }
    session.send(ContestStatePacket(doc.toString()))
  }

  /** Close the UI of [kind] on the client. */
  fun close(session: SessionContext, kind: String) = send(session, "close") { put("what", kind) }

  /**
   * Suspends until the player answers the UI [kind] (an action `<kind>.*` routed to [answer]), or
   * returns null after [timeoutMs] / when the player leaves.
   */
  suspend fun await(session: SessionContext, kind: String, timeoutMs: Long = 15 * 60_000L): JsonObject? {
    val d = CompletableDeferred<JsonObject>()
    waits[session to kind]?.cancel()
    waits[session to kind] = d
    return try {
      withTimeoutOrNull(timeoutMs) { d.await() }
    } finally {
      waits.remove(session to kind, d)
    }
  }

  /** True if a script waits on [kind] for this session and took the answer. */
  fun answer(session: SessionContext, kind: String, msg: JsonObject): Boolean =
      waits.remove(session to kind)?.complete(msg) ?: false

  fun cancelAll(session: SessionContext) {
    waits.keys.filter { it.first == session }.forEach { waits.remove(it)?.cancel() }
  }
}

/** Contest stats and ribbons per monster, cached over [ContestRepository]. */
@Singleton
class ContestStatsService @Inject constructor(private val repo: ContestRepository) {
  private val cache = ConcurrentHashMap<Long, MonContest>()

  suspend fun get(monId: Long): MonContest =
      cache[monId] ?: (repo.monContest(listOf(monId))[monId] ?: MonContest()).also { cache[monId] = it }

  suspend fun preload(monIds: Collection<Long>) {
    val missing = monIds.filter { !cache.containsKey(it) }
    if (missing.isEmpty()) return
    val loaded = repo.monContest(missing)
    for (id in missing) cache[id] = loaded[id] ?: MonContest()
  }

  /** For synchronous callers (the evolution check): reads through when not cached. */
  fun getBlocking(monId: Long): MonContest = cache[monId] ?: runBlocking { get(monId) }

  suspend fun set(monId: Long, c: MonContest) {
    cache[monId] = c
    repo.saveMonContest(monId, c)
  }

  /** The `conditions` document: the party's contest stats, for the summary and the case. */
  suspend fun conditionsJson(party: List<Pokemon>): JsonObjectBuilder.() -> Unit {
    preload(party.map { it.id })
    val rows = party.sortedBy { it.containerSlot }.map { it to get(it.id) }
    return {
      putJsonArray("mons") {
        for ((m, c) in rows) add(monJson(m, c))
      }
    }
  }

  companion object {
    fun monJson(m: Pokemon, c: MonContest) = buildJsonObject {
      put("id", m.id.toString())
      put("slot", m.containerSlot.toInt())
      put("dex", m.dexId)
      put("name", m.nickname)
      put("level", m.level.toInt())
      put("shiny", m.isShiny)
      put("egg", m.isEgg)
      put("nature", m.nature.name)
      put("cool", c.cool)
      put("beauty", c.beauty)
      put("cute", c.cute)
      put("smart", c.smart)
      put("tough", c.tough)
      put("sheen", c.sheen)
      putJsonArray("rankRibbons") { c.rankRibbons.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
      put("ribbons", c.ribbons)
    }
  }
}

/** The POKéBLOCK CASE: storage, tossing and feeding (pokeblock.c, use_pokeblock.c). */
@Singleton
class PokeblockService
@Inject
constructor(
    private val repo: ContestRepository,
    private val stats: ContestStatsService,
    private val characters: CharacterStore,
    private val docs: ContestDocs,
    private val names: ContestNames,
) {
  private val cases = ConcurrentHashMap<Long, MutableList<Pokeblock?>>()
  private val locks = ConcurrentHashMap<Long, Mutex>()

  private fun lock(charId: Long) = locks.getOrPut(charId) { Mutex() }

  suspend fun case(charId: Long): MutableList<Pokeblock?> =
      cases[charId] ?: repo.pokeblocks(charId).toMutableList().also { cases[charId] = it }

  /** GetFirstFreePokeblockSlot, -1 when full. */
  suspend fun firstFreeSlot(charId: Long): Int = case(charId).indexOfFirst { it == null }

  /** AddPokeblock. */
  suspend fun add(charId: Long, block: Pokeblock): Boolean =
      lock(charId).withLock {
        val c = case(charId)
        val slot = c.indexOfFirst { it == null }
        if (slot < 0) return@withLock false
        c[slot] = block
        repo.savePokeblocks(charId, c)
        true
      }

  suspend fun toss(charId: Long, slot: Int): Boolean =
      lock(charId).withLock {
        val c = case(charId)
        if (slot !in c.indices || c[slot] == null) return@withLock false
        c[slot] = null
        repo.savePokeblocks(charId, c)
        true
      }

  /**
   * Feeds the block in [slot] to party monster [monId]: the new stats, or a refusal text (the
   * sheen is maxed: "won't eat any more").
   */
  suspend fun feed(charId: Long, slot: Int, monId: Long): Pair<String, MonContest?> =
      lock(charId).withLock {
        val c = case(charId)
        val block = c.getOrNull(slot) ?: return@withLock "There is no POKéBLOCK there." to null
        val mon =
            characters.getCharacter(charId)?.pokemon?.firstOrNull { it.id == monId }
                ?: return@withLock "That POKéMON is not in your party." to null
        if (mon.isEgg) return@withLock "An EGG can't eat a POKéBLOCK." to null
        val before = stats.get(monId)
        val after =
            PokeblockRules.feed(before, mon.nature, block)
                ?: return@withLock "${mon.nickname.ifBlank { names.species(mon.dexId) }} won't eat any more…" to null
        c[slot] = null
        repo.savePokeblocks(charId, c)
        stats.set(monId, after)
        PokeblockRules.ateText(mon.nickname.ifBlank { names.species(mon.dexId) }, block, mon.nature) to
            after
      }

  fun blockJson(slot: Int, b: Pokeblock) = buildJsonObject {
    put("slot", slot)
    put("color", b.color)
    put("name", PokeblockRules.name(b))
    put("spicy", b.spicy)
    put("dry", b.dry)
    put("sweet", b.sweet)
    put("bitter", b.bitter)
    put("sour", b.sour)
    put("feel", b.feelLevel)
    put("level", b.level)
  }

  /** The `pokeblocks` document: the case and the party with its conditions. */
  suspend fun sendCase(session: SessionContext, charId: Long, open: Boolean, message: String = "") {
    val c = case(charId)
    val party = characters.getCharacter(charId)?.pokemon.orEmpty().sortedBy { it.containerSlot }
    stats.preload(party.map { it.id })
    val mons = party.map { ContestStatsService.monJson(it, stats.get(it.id)) }
    docs.send(session, "pokeblocks") {
      put("open", open)
      put("message", message)
      putJsonArray("blocks") { c.forEachIndexed { i, b -> if (b != null) add(blockJson(i, b)) } }
      putJsonArray("party") { mons.forEach { add(it) } }
    }
  }

  fun onLeave(charId: Long) {
    cases.remove(charId)
    locks.remove(charId)
  }
}

/** Per-player berry trees (berry.c), growing in real minutes like the RTC. */
@Singleton
class BerryTreeService
@Inject
constructor(
    private val repo: ContestRepository,
    private val maps: MapManager,
    private val npcs: NpcService,
    private val characters: CharacterStore,
    private val items: ItemRegistry,
    private val docs: ContestDocs,
) {
  private val data = ContestData.instance
  private val trees = ConcurrentHashMap<Long, MutableMap<Int, StoredBerryTree>>()
  private val locks = ConcurrentHashMap<Long, Mutex>()

  /** Tests and the dev command move the clock. */
  @Volatile var clock: () -> LocalDateTime = { LocalDateTime.now() }

  private fun lock(charId: Long) = locks.getOrPut(charId) { Mutex() }

  private suspend fun load(charId: Long): MutableMap<Int, StoredBerryTree> {
    trees[charId]?.let { return it }
    val m = repo.berryTrees(charId).toMutableMap()
    if (!repo.treesInitialized(charId)) {
      // data/scripts/new_game.inc setberrytree: planted, growth stopped until the player sees them
      val now = clock()
      for (init in data.initialBerryTrees) {
        val berry = data.berryByItem(init.item)?.type ?: continue
        val t = StoredBerryTree(BerryTreeRules.plant(berry, init.stage, false, LiveRng), now)
        m[init.tree] = t
        repo.saveBerryTree(charId, init.tree, t)
      }
      repo.setTreesInitialized(charId)
    }
    trees[charId] = m
    return m
  }

  /** The tree brought up to date with the time passed (BerryTreeTimeUpdate). */
  suspend fun tree(charId: Long, treeId: Int): BerryTree =
      lock(charId).withLock { current(charId, treeId) }

  private suspend fun current(charId: Long, treeId: Int): BerryTree {
    val m = load(charId)
    val s = m[treeId] ?: return BerryTree.BLANK
    val now = clock()
    val minutes = Duration.between(s.updatedAt, now).toMinutes()
    if (minutes <= 0) return s.tree
    val t = BerryTreeRules.advance(s.tree, minutes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), LiveRng)
    val updated = StoredBerryTree(t, s.updatedAt.plusMinutes(minutes))
    m[treeId] = updated
    repo.saveBerryTree(charId, treeId, updated)
    return t
  }

  private suspend fun put(charId: Long, treeId: Int, t: BerryTree) {
    val s = StoredBerryTree(t, clock())
    load(charId)[treeId] = s
    repo.saveBerryTree(charId, treeId, s)
  }

  suspend fun update(charId: Long, treeId: Int, change: (BerryTree) -> BerryTree?): BerryTree? =
      lock(charId).withLock {
        val t = current(charId, treeId)
        val n = change(t) ?: return@withLock null
        put(charId, treeId, n)
        n
      }

  /** Advances every tree of [charId] by [minutes] (the dev command). */
  suspend fun fastForward(charId: Long, minutes: Int) =
      lock(charId).withLock {
        val m = load(charId)
        for (id in m.keys.toList()) {
          val t = current(charId, id)
          put(charId, id, BerryTreeRules.advance(t, minutes, LiveRng))
        }
      }

  /** The berry tree a map npc is (its BerryTreeScript object), or null. */
  fun treeIdForEntity(state: PlayerState, entityId: Long): Int? {
    val map = maps.getMap(state.regionId, state.bankId, state.mapId) ?: return null
    val npc =
        map.npcs.firstOrNull {
          it.script == "BerryTreeScript" &&
              npcs.getNpcEntityId(state.regionId, state.bankId, state.mapId, it.entityIdx) == entityId
        } ?: return null
    return data.berryTrees.firstOrNull { it.map == map.name && it.x == npc.x && it.y == npc.y }?.tree
  }

  /** The tree the player faces, for the Wailmer Pail used from the bag. */
  fun treeIdInFront(state: PlayerState): Int? {
    val map = maps.getMap(state.regionId, state.bankId, state.mapId) ?: return null
    var x = state.x.toInt()
    var y = state.y.toInt()
    when (state.facingDirection) {
      de.fiereu.openmmo.common.enums.Direction.UP -> y--
      de.fiereu.openmmo.common.enums.Direction.DOWN -> y++
      de.fiereu.openmmo.common.enums.Direction.LEFT -> x--
      de.fiereu.openmmo.common.enums.Direction.RIGHT -> x++
      else -> {}
    }
    return data.berryTrees.firstOrNull { it.map == map.name && it.x == x && it.y == y }?.tree
  }

  /**
   * The `berryTrees` document for the player's current map: every tree spot with its berry and
   * stage, which the browser draws over the tree object (SetBerryTreesSeen allows their growth).
   */
  suspend fun sendMapTrees(session: SessionContext, state: PlayerState) {
    val charId = state.characterId ?: return
    val map = maps.getMap(state.regionId, state.bankId, state.mapId) ?: return
    val spots = data.berryTrees.filter { it.map == map.name }
    val rows =
        lock(charId).withLock {
          spots.map { s ->
            var t = current(charId, s.tree)
            if (t.stopGrowth) {
              t = t.copy(stopGrowth = false)
              put(charId, s.tree, t)
            }
            s to t
          }
        }
    docs.send(session, "berryTrees") {
      put("map", map.name)
      put("region", state.regionId)
      put("bank", state.bankId)
      put("mapId", state.mapId)
      putJsonArray("trees") {
        for ((s, t) in rows) {
          add(
              buildJsonObject {
                put("tree", s.tree)
                put("x", s.x)
                put("y", s.y)
                put("berry", t.berry)
                put("stage", t.stage)
                put("name", if (t.berry != 0) data.berry(t.berry).name else "")
                put("watered", t.wateredCount)
                put("yield", t.berryYield)
              })
        }
      }
    }
  }

  /** The player's berries, as (decomp berry type, wire item id, quantity). */
  fun berriesInBag(charId: Long): List<Triple<BerryDef, Int, Int>> {
    val bag = characters.getCharacter(charId)?.items ?: return emptyList()
    return data.berries.mapNotNull { b ->
      val def = ItemNames.byDecompName(b.item.removePrefix("ITEM_")) ?: return@mapNotNull null
      val id = items.idOrNull(def) ?: return@mapNotNull null
      val q = bag[id] ?: 0
      if (q > 0) Triple(b, id, q) else null
    }
  }

  fun itemIdOf(b: BerryDef): Int? =
      ItemNames.byDecompName(b.item.removePrefix("ITEM_"))?.let { items.idOrNull(it) }

  /** Adds or takes items and syncs the bag. */
  suspend fun changeItem(session: SessionContext, charId: Long, itemId: Int, qty: Int): Boolean {
    if (!characters.addItem(charId, itemId, qty)) return false
    characters.getCharacter(charId)?.items?.let { session.send(storyItemStacksPacket(it)) }
    return true
  }

  /**
   * The POKéBLOCK CASE: the item catalogue has no such item, so owning it is the decomp's
   * FLAG_RECEIVED_POKEBLOCK_CASE, which the contest reception sets when it hands the case over.
   */
  fun hasCase(charId: Long): Boolean =
      characters.getCharacter(charId)?.storyFlags?.contains("hoenn/FLAG_RECEIVED_POKEBLOCK_CASE") == true

  fun hasItem(charId: Long, decompName: String): Boolean {
    if (decompName == "POKEBLOCK_CASE") return hasCase(charId)
    val def = ItemNames.byDecompName(decompName) ?: return false
    val id = items.idOrNull(def) ?: return false
    return (characters.getCharacter(charId)?.items?.get(id) ?: 0) > 0
  }

  fun onLeave(charId: Long) {
    trees.remove(charId)
    locks.remove(charId)
  }

  @Suppress("unused") private fun unused(s: SessionContext) = s.attributes[PLAYER_STATE]
}

/** Move and species names for the contest screen. */
@Singleton
class ContestNames
@Inject
constructor(private val moves: MoveRegistry, private val species: SpeciesRegistry) {
  fun move(id: Int): String = moves.get(id)?.name?.uppercase() ?: "MOVE $id"

  fun species(dex: Int): String = species.get(dex)?.name?.uppercase() ?: "#$dex"

  init {
    log.debug { "contest names ready" }
  }
}
