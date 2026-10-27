package de.fiereu.openmmo.server.game.contest

import de.fiereu.network.SessionContext
import de.fiereu.openmmo.server.game.session.PlayerState
import de.fiereu.openmmo.server.game.storage.CharacterStore
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private val log = KotlinLogging.logger {}

/** One human at a blender. */
private class BlendPlayer(
    val session: SessionContext,
    val charId: Long,
    val name: String,
    val done: CompletableDeferred<Unit> = CompletableDeferred(),
) {
  @Volatile var berry: Int = 0
  @Volatile var gone = false
  val presses = ConcurrentLinkedQueue<Int>()
}

private class BlendRoom(val key: String, val npcOpponents: Int, val berryMaster: Boolean) {
  val players = mutableListOf<BlendPlayer>()
  var started = false
  val full = CompletableDeferred<Unit>()
}

/**
 * The Berry Blender with other online players: whoever uses the same blender (map + its NPC
 * count, VAR_0x8004 of DoBerryBlending) within [JOIN_WINDOW_MS] blends together, up to four;
 * NPCs (sBlenderOpponentsNames) fill the seats the blender's script asks for. Each player picks a
 * berry first (the blender's berry menu); then [BlenderEngine] runs at the GBA's 60 frames per
 * second on the server, the browser presses A with the arrow position it saw, and everybody gets
 * the Pokéblock CalculatePokeblock makes.
 */
@Singleton
class BlenderService
@Inject
constructor(
    private val docs: ContestDocs,
    private val pokeblocks: PokeblockService,
    private val trees: BerryTreeService,
    private val characters: CharacterStore,
    private val repo: ContestRepository,
    private val scope: CoroutineScope,
) {
  private val data = ContestData.instance
  private val rooms = ConcurrentHashMap<String, BlendRoom>()
  private val lock = Mutex()
  private val active = ConcurrentHashMap<Long, BlendPlayer>()

  internal var joinWindowMs = JOIN_WINDOW_MS
  internal var rng: ContestRng = LiveRng

  /**
   * DoBerryBlending: the berry menu, the join window, the game and its result. Returns after the
   * player said no to "blend another BERRY?" (or ran out of berries / case space).
   */
  suspend fun blend(
      session: SessionContext,
      state: PlayerState,
      npcOpponents: Int,
      berryMaster: Boolean,
  ) {
    val charId = state.characterId ?: return
    val name = characters.getCharacter(charId)?.info?.name ?: "PLAYER"
    val key = "${state.regionId}:${state.bankId}:${state.mapId}:$npcOpponents:$berryMaster"
    while (true) {
      if (pokeblocks.firstFreeSlot(charId) < 0) {
        docs.send(session, "blender") {
          put("phase", "message")
          put("text", "Your POKéBLOCK CASE is full.")
        }
        docs.await(session, "blender", 60_000)
        break
      }
      val berries = trees.berriesInBag(charId)
      if (berries.isEmpty()) {
        docs.send(session, "blender") {
          put("phase", "message")
          put("text", "You've run out of BERRIES for\nblending in the BERRY BLENDER.")
        }
        docs.await(session, "blender", 60_000)
        break
      }
      docs.send(session, "blender") {
        put("phase", "choose")
        putJsonArray("berries") {
          for ((b, _, q) in berries)
              add(
                  buildJsonObject {
                    put("berry", b.type)
                    put("name", b.name)
                    put("qty", q)
                  })
        }
      }
      val pick = docs.await(session, "blender") ?: break
      if (pick.str("t") != "blender.berry") break
      val type = pick.int("berry") ?: break
      val (def, itemId, _) = berries.firstOrNull { it.first.type == type } ?: break
      // The berry goes into the blender (it is used even if nobody else joins).
      if (!trees.changeItem(session, charId, itemId, -1)) break
      val me = BlendPlayer(session, charId, name).also { it.berry = def.type }
      active[charId] = me
      try {
        join(key, npcOpponents, berryMaster, me)
        me.done.await()
      } finally {
        active.remove(charId)
      }
      // "Would you like to blend another BERRY?"
      val again = docs.await(session, "blender") ?: break
      if (again.str("t") != "blender.again") break
    }
    docs.close(session, "blender")
  }

  private suspend fun join(key: String, npc: Int, master: Boolean, p: BlendPlayer) {
    val room =
        lock.withLock {
          val r = rooms[key]?.takeIf { !it.started && it.players.size < 4 }
          val room = r ?: BlendRoom(key, npc, master).also { rooms[key] = it }
          room.players += p
          if (room.players.size == 4) room.full.complete(Unit)
          if (r == null) scope.launch { runWhenReady(room) }
          room
        }
    waitingDoc(room)
  }

  private fun waitingDoc(r: BlendRoom) {
    for (p in r.players.toList())
        docs.send(p.session, "blender") {
          put("phase", "wait")
          put("waitMs", joinWindowMs)
          putJsonArray("players") { r.players.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.name)) } }
        }
  }

  /** The host may start before the window ends. */
  fun startNow(charId: Long) {
    val p = active[charId] ?: return
    rooms.values.firstOrNull { p in it.players && !it.started }?.full?.complete(Unit)
  }

  private suspend fun runWhenReady(r: BlendRoom) {
    withTimeoutOrNull(joinWindowMs) { r.full.await() }
    val humans =
        lock.withLock {
          r.started = true
          rooms.remove(r.key, r)
          r.players.filter { !it.gone }.toList()
        }
    if (humans.isEmpty()) return
    try {
      play(r, humans)
    } catch (e: Exception) {
      log.error(e) { "blender failed" }
    } finally {
      humans.forEach { it.done.complete(Unit) }
    }
  }

  private suspend fun play(r: BlendRoom, humans: List<BlendPlayer>) {
    // A single player blends with the blender's NPCs (InitLocalPlayers); a group of players fills
    // the NPC seats the blender has beyond them.
    val npcSeats = (r.npcOpponents - (humans.size - 1)).coerceAtLeast(0).coerceAtMost(4 - humans.size)
    val seats = (humans.size + npcSeats).coerceAtLeast(2)
    val npcCount = seats - humans.size
    val npcNames =
        when {
          npcCount == 1 && humans.size == 1 ->
              listOf(data.blenderNames.getValue(if (r.berryMaster) "MASTER" else "MISTER"))
          npcCount == 1 -> listOf(data.blenderNames.getValue("MISTER"))
          npcCount == 2 -> listOf(data.blenderNames.getValue("DUDE"), data.blenderNames.getValue("LASSIE"))
          npcCount == 3 ->
              listOf(
                  data.blenderNames.getValue("MISS"),
                  data.blenderNames.getValue("LADDIE"),
                  data.blenderNames.getValue("LASSIE"))
          else -> emptyList()
        }
    // NPC behaviour: Task_HandleOpponent1..3, the Berry Master always hits Best.
    val npcKinds =
        List(npcCount) { i -> if (r.berryMaster && npcCount == 1 && humans.size == 1) 4 else i + 1 }
    val kinds = List(humans.size) { 0 } + npcKinds
    // SetOpponentsBerryData from the first player's berry
    val npcBerries =
        if (npcCount > 0)
            BlenderEngine.opponentBerries(humans[0].berry, npcCount + 1, r.berryMaster && humans.size == 1)
                .take(npcCount)
        else emptyList()
    val berries = humans.map { it.berry } + npcBerries
    val names = humans.map { it.name } + npcNames
    val engine = BlenderEngine(seats, kinds, rng, data)
    fun seatsJson() = putJsonArrayOf(names, berries, kinds, engine)
    // the berries fall in
    broadcast(humans) { me ->
      put("phase", "start")
      put("you", me)
      put("seats", seatsJson())
    }
    delay(START_MS)
    val hits = mutableListOf<Pair<Int, Int>>()
    var lastSend = 0
    val t0 = System.nanoTime()
    while (!engine.finished && humans.any { !it.gone } && engine.frame < MAX_FRAMES) {
      val presses = mutableMapOf<Int, Int>()
      humans.forEachIndexed { seat, p ->
        val pos = p.presses.poll() ?: return@forEachIndexed
        // The client says where it saw the arrow; trust it only if the arrow was there.
        presses[seat] = if (engine.recentlyAt(pos)) pos else engine.arrowPos
      }
      hits += engine.step(presses)
      if (engine.frame - lastSend >= SEND_EVERY || engine.finished) {
        lastSend = engine.frame
        val batch = hits.toList()
        hits.clear()
        broadcast(humans) { me ->
          put("phase", "play")
          put("you", me)
          put("seats", seatsJson())
          put("frame", engine.frame)
          put("arrow", engine.arrowPos)
          put("speed", engine.speed)
          put("progress", engine.progress)
          put("rpm", BlenderEngine.arrowSpeedToRPM(engine.speed))
          put("maxRpm", engine.maxRPM)
          putJsonArray("hits") {
            for ((s, c) in batch)
                add(
                    buildJsonObject {
                      put("seat", s)
                      put(
                          "score",
                          when (c) {
                            BlenderEngine.SCORE_BEST -> "best"
                            BlenderEngine.SCORE_GOOD -> "good"
                            else -> "miss"
                          })
                    })
          }
        }
      }
      // 60 frames per second, on wall time
      val next = t0 + engine.frame * FRAME_NS
      val wait = (next - System.nanoTime()) / 1_000_000
      if (wait > 0) delay(wait)
    }
    val block = BlenderEngine.calculatePokeblock(berries, engine.maxRPM, rng, data)
    // SortScores: by Best, then Good, then fewer Misses
    val order =
        (0 until seats).sortedWith(
            compareByDescending<Int> { engine.scores[it][0] }
                .thenByDescending { engine.scores[it][1] }
                .thenBy { engine.scores[it][2] })
    for ((seat, p) in humans.withIndex()) {
      if (p.gone) continue
      val got = pokeblocks.add(p.charId, block)
      val record = repo.blenderRecord(p.charId)
      val newRecord = engine.maxRPM > record
      if (newRecord) repo.setBlenderRecord(p.charId, engine.maxRPM)
      docs.send(p.session, "blender") {
        put("phase", "result")
        put("you", seat)
        put("seats", seatsJson())
        put("maxRpm", engine.maxRPM)
        put("record", maxOf(record, engine.maxRPM))
        put("newRecord", newRecord)
        put("block", pokeblocks.blockJson(-1, block))
        put("received", got)
        putJsonArray("ranking") {
          for (s in order)
              add(
                  buildJsonObject {
                    put("seat", s)
                    put("name", names[s])
                    put("best", engine.scores[s][0])
                    put("good", engine.scores[s][1])
                    put("miss", engine.scores[s][2])
                  })
        }
      }
    }
  }

  private fun putJsonArrayOf(
      names: List<String>,
      berries: List<Int>,
      kinds: List<Int>,
      engine: BlenderEngine,
  ) =
      kotlinx.serialization.json.buildJsonArray {
        for (i in names.indices)
            add(
                buildJsonObject {
                  put("name", names[i])
                  put("npc", kinds[i] != 0)
                  put("berry", berries[i])
                  put("berryName", data.berry(berries[i]).name)
                  put("arrow", engine.arrowIdOf(i))
                  put("hitStart", data.arrowHitRangeStart[engine.arrowIdOf(i)])
                })
      }

  private fun broadcast(humans: List<BlendPlayer>, body: kotlinx.serialization.json.JsonObjectBuilder.(Int) -> Unit) {
    humans.forEachIndexed { seat, p -> if (!p.gone) docs.send(p.session, "blender") { body(seat) } }
  }

  /** ShowBerryBlenderRecordWindow: the player's best RPM (berryBlenderRecords). */
  suspend fun showRecord(session: SessionContext, charId: Long) {
    val r = repo.blenderRecord(charId)
    docs.send(session, "blender") {
      put("phase", "record")
      put("record", r)
      put("text", "BERRY BLENDER\nMAXIMUM SPEED RECORD!\n${r / 100}.${"%02d".format(r % 100)} RPM")
    }
    docs.await(session, "blender", 60_000)
    docs.close(session, "blender")
  }

  fun onAction(charId: Long, msg: JsonObject) {
    when (msg.str("t")) {
      "blender.press" -> {
        val pos = msg.int("pos") ?: return
        active[charId]?.presses?.add(pos and 0xFFFF)
      }
      "blender.start" -> startNow(charId)
    }
  }

  fun onLeave(charId: Long) {
    active.remove(charId)?.let { it.gone = true }
  }

  companion object {
    const val JOIN_WINDOW_MS = 10_000L
    const val START_MS = 2_500L
    const val SEND_EVERY = 6
    const val FRAME_NS = 1_000_000_000L / 60
    /** A blend that never fills the bar stops after five minutes. */
    const val MAX_FRAMES = 60 * 300
  }
}
