package de.fiereu.openmmo.server.game.contest

import de.fiereu.network.SessionContext
import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.server.game.session.PlayerState
import de.fiereu.openmmo.server.game.session.SessionRegistry
import de.fiereu.openmmo.server.game.storage.CharacterStore
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private val log = KotlinLogging.logger {}

/** A player's entry: which party monster, in which category and rank. */
data class ContestEntry(val slot: Int, val category: Int, val rank: Int)

/** What one player keeps of a finished contest, for the hall specials and the prize. */
class ContestResultView(
    val engine: ContestEngine,
    /** The player's contestant index in the engine. */
    val me: Int,
    val monId: Long,
    val category: Int,
    val rank: Int,
) {
  /**
   * The decomp's single-player scripts assume the player is contestant 3; each player sees the
   * contest rotated so that their own monster is index 3 ([toView] / [fromView]).
   */
  fun toView(c: Int): Int = (c - me + 3 + Contest.COUNT) % Contest.COUNT

  fun fromView(v: Int): Int = (v - 3 + me + Contest.COUNT) % Contest.COUNT

  val won: Boolean
    get() = engine.finalStandings[me] == 0

  val winnerView: Int
    get() = toView(engine.winner)

  val totalPoints: Int
    get() = engine.totalPoints[me]
}

private class Human(
    val session: SessionContext,
    val state: PlayerState,
    val charId: Long,
    val mon: Pokemon,
    val contestMon: ContestMon,
    val done: CompletableDeferred<ContestResultView?> = CompletableDeferred(),
) {
  @Volatile var choice: Int? = null
  @Volatile var ready = false
  @Volatile var gone = false
}

private class Lobby(val category: Int, val rank: Int, val postgame: Boolean) {
  val humans = mutableListOf<Human>()
  val full = CompletableDeferred<Unit>()
  var started = false
}

/**
 * Hoenn contests in an MMO: the reception picks a monster, category and rank; entrants of the same
 * category and rank who enter within [JOIN_WINDOW_MS] compete together (up to four players), NPC
 * opponents from gContestOpponents fill the other seats (SetContestants / SetLinkAIContestants).
 * The appeal phase runs on [ContestEngine]; every human picks a move per round, the rounds stream
 * to each player's contest screen as `contest` documents.
 */
@Singleton
class ContestService
@Inject
constructor(
    private val docs: ContestDocs,
    private val stats: ContestStatsService,
    private val characters: CharacterStore,
    private val names: ContestNames,
    private val repo: ContestRepository,
    private val sessions: SessionRegistry,
    private val scope: CoroutineScope,
) {
  private val data = ContestData.instance
  private val lobbies = ConcurrentHashMap<Pair<Int, Int>, Lobby>()
  private val lobbyLock = Mutex()
  private val playing = ConcurrentHashMap<Long, Human>()
  private val results = ConcurrentHashMap<Long, ContestResultView>()
  /** VAR_CONTEST_CATEGORY / RANK / the chosen party slot, per character (gSpecialVar_Contest*). */
  val entries = ConcurrentHashMap<Long, ContestEntry>()

  /** Tests shorten the waits. */
  internal var joinWindowMs = JOIN_WINDOW_MS
  internal var moveTimeoutMs = MOVE_TIMEOUT_MS
  internal var rng: ContestRng = LiveRng

  fun result(charId: Long): ContestResultView? = results[charId]

  // ------------------------------------------------------------------ entry

  /** GetContestEntryEligibility. */
  suspend fun eligibility(mon: Pokemon, category: Int, rank: Int): Int {
    if (mon.isEgg) return Contest.CANT_ENTER_EGG
    if (mon.hp.toInt() == 0) return Contest.CANT_ENTER_FAINTED
    if (category !in 0..4) return Contest.CANT_ENTER
    val ribbon = stats.get(mon.id).ribbon(category)
    return when {
      ribbon > rank -> Contest.CAN_ENTER_HIGH_RANK
      ribbon >= rank -> Contest.CAN_ENTER_EQUAL_RANK
      else -> Contest.CANT_ENTER
    }
  }

  private fun party(charId: Long) =
      characters.getCharacter(charId)?.pokemon.orEmpty().sortedBy { it.containerSlot }

  /**
   * The reception's menus (MULTI_CONTEST_RANK, MULTI_CONTEST_TYPE, choosecontestmon) as one screen:
   * the player picks rank, category and monster, or cancels (null).
   */
  suspend fun chooseEntry(session: SessionContext, state: PlayerState): ContestEntry? {
    val charId = state.characterId ?: return null
    val party = party(charId)
    stats.preload(party.map { it.id })
    val rows = party.map { m ->
      val c = stats.get(m.id)
      val elig =
          (0..4).map { cat -> (0..3).map { r -> eligibility(m, cat, r) } }
      Triple(m, c, elig)
    }
    docs.send(session, "contestEntry") {
      put("open", true)
      putJsonArray("party") {
        for ((m, c, elig) in rows) {
          add(
              buildJsonObject {
                ContestStatsService.monJson(m, c, names).forEach { (k, v) -> put(k, v) }
                put(
                    "eligible",
                    buildJsonArray {
                      for (cat in elig) add(buildJsonArray { cat.forEach { add(JsonPrimitive(it)) } })
                    })
              })
        }
      }
    }
    val answer = docs.await(session, "contestEntry") ?: return null
    docs.close(session, "contestEntry")
    if (answer.str("t") != "contestEntry.choose") return null
    val slot = answer.int("slot") ?: return null
    val cat = answer.int("category") ?: return null
    val rank = answer.int("rank") ?: return null
    if (slot !in party.indices || cat !in 0..4 || rank !in 0..3) return null
    return ContestEntry(slot, cat, rank).also { entries[charId] = it }
  }

  // ------------------------------------------------------------------ matchmaking

  /**
   * startcontest: enters the chosen monster and suspends until the contest (and its results
   * screen) is over. Returns the player's view of the result, or null if it could not run.
   */
  suspend fun runContest(
      session: SessionContext,
      state: PlayerState,
      entry: ContestEntry,
      postgame: Boolean,
  ): ContestResultView? {
    val charId = state.characterId ?: return null
    val mon = party(charId).getOrNull(entry.slot) ?: return null
    val c = stats.get(mon.id)
    val human =
        Human(
            session,
            state,
            charId,
            mon,
            contestMonOf(mon, c, characters.getCharacter(charId)?.info?.name ?: "PLAYER"))
    val key = entry.category to entry.rank
    val lobby =
        lobbyLock.withLock {
          val existing = lobbies[key]?.takeIf { !it.started && it.humans.size < Contest.COUNT }
          val l = existing ?: Lobby(entry.category, entry.rank, postgame).also { lobbies[key] = it }
          l.humans += human
          if (l.humans.size == Contest.COUNT) l.full.complete(Unit)
          if (existing == null) scope.launch { startWhenReady(key, l) }
          l
        }
    lobbyStatus(lobby)
    playing[charId] = human
    return try {
      human.done.await()
    } finally {
      playing.remove(charId)
    }
  }

  private fun lobbyStatus(l: Lobby) {
    for (h in l.humans.toList())
        docs.send(h.session, "contest") {
          put("phase", "waiting")
          put("category", l.category)
          put("rank", l.rank)
          put("players", l.humans.size)
          put("waitMs", joinWindowMs)
          putJsonArray("names") { l.humans.forEach { add(it.contestMon.trainerName) } }
        }
  }

  private suspend fun startWhenReady(key: Pair<Int, Int>, l: Lobby) {
    withTimeoutOrNull(joinWindowMs) { l.full.await() }
    val humans =
        lobbyLock.withLock {
          l.started = true
          lobbies.remove(key, l)
          l.humans.filter { !it.gone }.toList()
        }
    if (humans.isEmpty()) return
    try {
      play(l, humans)
    } catch (e: Exception) {
      log.error(e) { "contest failed" }
      humans.forEach {
        docs.close(it.session, "contest")
        it.done.complete(null)
      }
    }
  }

  /** CreateContestMonFromParty (scarves add 20). */
  fun contestMonOf(mon: Pokemon, c: MonContest, trainer: String): ContestMon {
    val held = de.fiereu.openmmo.server.game.battle.ItemNames
    fun scarf(name: String) =
        held.byDecompName(name)?.let { def -> mon.heldItem != 0 && heldIs(mon.heldItem, def) } == true
    var cool = c.cool
    var beauty = c.beauty
    var cute = c.cute
    var smart = c.smart
    var tough = c.tough
    when {
      scarf("RED_SCARF") -> cool += 20
      scarf("BLUE_SCARF") -> beauty += 20
      scarf("PINK_SCARF") -> cute += 20
      scarf("GREEN_SCARF") -> smart += 20
      scarf("YELLOW_SCARF") -> tough += 20
    }
    return ContestMon(
        mon.dexId,
        mon.nickname.ifBlank { names.species(mon.dexId) },
        trainer,
        mon.moves.map { it.id.toInt() }.let { it + List(4 - it.size) { 0 } }.take(4),
        cool.coerceAtMost(255),
        beauty.coerceAtMost(255),
        cute.coerceAtMost(255),
        smart.coerceAtMost(255),
        tough.coerceAtMost(255),
        c.sheen,
        isPlayer = true,
        shiny = mon.isShiny)
  }

  /** Set by the module from ItemRegistry: does wire item id [held] equal [def]? */
  internal var heldIs: (Int, de.fiereu.openmmo.items.ItemDef) -> Boolean = { _, _ -> false }

  /** SetContestants / SetLinkAIContestants: three (or fewer) random suitable opponents. */
  fun pickOpponents(category: Int, rank: Int, count: Int, postgame: Boolean): List<ContestMon> {
    val pool =
        data.opponents
            .filter { it.rank == rank && it.pools[category] }
            .filter {
              if (postgame) it.filter != Contest.FILTER_NO_POSTGAME
              else it.filter != Contest.FILTER_ONLY_POSTGAME
            }
            .toMutableList()
    return List(count) {
      val o = pool.removeAt((rng.next() and 0xFFFF) % pool.size)
      ContestMon(
          o.dex,
          o.nickname,
          o.trainer,
          o.moves,
          o.stats[0],
          o.stats[1],
          o.stats[2],
          o.stats[3],
          o.stats[4],
          o.stats[5],
          aiFlags = o.aiFlags,
          trainerGfx = o.gfx)
    }
  }

  // ------------------------------------------------------------------ the contest

  private suspend fun play(l: Lobby, humans: List<Human>) {
    // Link style: players first, then NPCs (SetLinkAIContestants). One player alone takes the
    // last seat like TryPutPlayerLast.
    val npcs = pickOpponents(l.category, l.rank, Contest.COUNT - humans.size, l.postgame)
    val mons: List<ContestMon>
    val humanIndex: Map<Human, Int>
    if (humans.size == 1) {
      mons = npcs + humans[0].contestMon
      humanIndex = mapOf(humans[0] to 3)
    } else {
      mons = humans.map { it.contestMon } + npcs
      humanIndex = humans.withIndex().associate { (i, h) -> h to i }
    }
    val e = ContestEngine(l.category, l.rank, mons, rng, data, names::move)
    // Primary judging: the condition round, shown with each contestant's hearts.
    broadcast(humans, humanIndex) { h, me -> introDoc(e, me) }
    delay(INTRO_MS)
    while (!e.finished) {
      // Move selection
      for (h in humans) {
        h.choice = null
        h.ready = false
      }
      broadcast(humans, humanIndex) { _, me -> selectDoc(e, me) }
      val deadline = System.currentTimeMillis() + moveTimeoutMs
      while (System.currentTimeMillis() < deadline &&
          humans.any { !it.gone && it.choice == null && !e.isTurnDisabled(humanIndex.getValue(it)) })
          delay(100)
      val choices = humans.associate { h ->
        val me = humanIndex.getValue(h)
        me to (h.choice ?: e.mons[me].moves.indexOfFirst { it != 0 }.coerceAtLeast(0))
      }
      val round = e.appealNumber
      val events = e.playRound(choices)
      for (h in humans) h.ready = false
      broadcast(humans, humanIndex) { _, me -> roundDoc(e, me, round, events) }
      // Every client animates the round; continue when all are done or the time is up.
      val animMs = (events.size * EVENT_MS).coerceIn(3000L, 90_000L)
      val until = System.currentTimeMillis() + animMs
      while (System.currentTimeMillis() < until && humans.any { !it.gone && !it.ready }) delay(100)
    }
    // Results (showcontestresults)
    for (h in humans) h.ready = false
    broadcast(humans, humanIndex) { _, me -> resultsDoc(e, me) }
    val until = System.currentTimeMillis() + RESULTS_MS
    while (System.currentTimeMillis() < until && humans.any { !it.gone && !it.ready }) delay(100)
    for (h in humans) {
      docs.close(h.session, "contest")
      val view =
          ContestResultView(e, humanIndex.getValue(h), h.mon.id, l.category, l.rank)
      results[h.charId] = view
      h.done.complete(view)
    }
  }

  private fun broadcast(
      humans: List<Human>,
      index: Map<Human, Int>,
      doc: (Human, Int) -> JsonObject,
  ) {
    for (h in humans) {
      if (h.gone) continue
      val body = doc(h, index.getValue(h))
      docs.send(h.session, "contest") { body.forEach { (k, v) -> put(k, v) } }
    }
  }

  private fun contestantsJson(e: ContestEngine, me: Int) = buildJsonArray {
    for (i in 0 until Contest.COUNT) {
      val m = e.mons[i]
      val s = e.status[i]
      add(
          buildJsonObject {
            put("index", i)
            put("you", i == me)
            put("player", m.isPlayer)
            put("dex", m.dex)
            put("shiny", m.shiny)
            put("name", m.nickname)
            put("trainer", m.trainerName)
            put("gfx", m.trainerGfx)
            put("round1", e.round1Points[i])
            put("hearts", s.pointTotal)
            put("turn", e.turnOrder[i])
            put("condition", s.condition / 10)
            put("status", e.statusSymbol(i))
            put("attention", s.hasJudgesAttention)
          })
    }
  }

  private fun header(e: ContestEngine, me: Int, phase: String) = buildJsonObject {
    put("phase", phase)
    put("category", e.category)
    put("rank", e.rank)
    put("appeal", e.appealNumber)
    put("applause", e.applauseLevel)
    put("you", me)
    put("contestants", contestantsJson(e, me))
  }

  private fun JsonObject.plus(vararg extra: Pair<String, kotlinx.serialization.json.JsonElement>) =
      JsonObject(this + extra.toMap())

  private fun introDoc(e: ContestEngine, me: Int): JsonObject =
      header(e, me, "intro")
          .plus(
              "title" to
                  JsonPrimitive(
                      "${Contest.RANK_NAMES[e.rank]} RANK ${Contest.CATEGORY_NAMES[e.category]} CONTEST"))

  /** The move select screen: each move's category, hearts, jam hearts, description, hints. */
  private fun selectDoc(e: ContestEngine, me: Int): JsonObject {
    val s = e.status[me]
    val moves = buildJsonArray {
      e.mons[me].moves.forEachIndexed { slot, id ->
        if (id == 0) return@forEachIndexed
        val def = data.move(id)
        val eff = data.effects[def.effect]
        add(
            buildJsonObject {
              put("slot", slot)
              put("id", id)
              put("name", names.move(id))
              put("category", def.category)
              put("appeal", eff.appeal / 10)
              put("jam", eff.jam / 10)
              put("description", data.effectDescriptions.getOrElse(def.effect) { "" })
              // DrawMoveSelectScreen: a combo finisher is highlighted, a repeat greyed
              put("combo", s.prevMove != 0 && e.areMovesCombo(s.prevMove, id) != 0 && s.hasJudgesAttention)
              put("repeat", id == s.prevMove && def.effect != REPETITION_NOT_BORING)
            })
      }
    }
    return header(e, me, "select")
        .plus(
            "prompt" to JsonPrimitive(e.appealPrompt(me)),
            "canMove" to JsonPrimitive(!e.isTurnDisabled(me)),
            "moves" to moves,
            "timeoutMs" to JsonPrimitive(moveTimeoutMs))
  }

  private fun roundDoc(
      e: ContestEngine,
      me: Int,
      round: Int,
      events: List<ContestEvent>,
  ): JsonObject {
    val ev = buildJsonArray {
      for (x in events) {
        if (x.kind == "round" && x.who != me) continue
        add(
            buildJsonObject {
              put("k", x.kind)
              put("who", x.who)
              if (x.text.isNotEmpty()) put("text", x.text)
              put("a", x.a)
              put("b", x.b)
              if (x.move != 0) {
                put("move", x.move)
                put("moveName", names.move(x.move))
                put("moveCategory", data.move(x.move).category)
              }
            })
      }
    }
    return header(e, me, "appeals").plus("round" to JsonPrimitive(round), "events" to ev)
  }

  private fun resultsDoc(e: ContestEngine, me: Int): JsonObject {
    val rows = buildJsonArray {
      for (i in 0 until Contest.COUNT) {
        add(
            buildJsonObject {
              put("index", i)
              put("place", e.finalStandings[i])
              put("round1", e.round1Points[i])
              put("round2", e.round2Points[i])
              put("total", e.totalPoints[i])
            })
      }
    }
    val winner = e.winner
    return header(e, me, "results")
        .plus(
            "results" to rows,
            "winner" to JsonPrimitive(winner),
            "text" to
                JsonPrimitive(
                    "The winner is ${e.mons[winner].trainerName}'s ${e.mons[winner].nickname}!"))
  }

  // ------------------------------------------------------------------ actions

  fun onAction(charId: Long, msg: JsonObject) {
    val h = playing[charId] ?: return
    when (msg.str("t")) {
      "contest.move" -> h.choice = msg.int("slot")?.coerceIn(0, 3)
      "contest.ready" -> h.ready = true
    }
  }

  fun onLeave(charId: Long) {
    playing[charId]?.let {
      it.gone = true
      it.ready = true
    }
    entries.remove(charId)
  }

  // ------------------------------------------------------------------ prizes

  /** GiveMonContestRibbon: the rank ribbon for a win, at most one rank above what it had. */
  suspend fun giveContestRibbon(view: ContestResultView): Boolean {
    if (!view.won) return false
    val c = stats.get(view.monId)
    val r = c.ribbon(view.category)
    if (r <= view.rank && r <= Contest.RANK_MASTER) {
      stats.set(view.monId, c.withRibbon(view.category, r + 1))
      return true
    }
    return false
  }

  /** ShouldReadyContestArtist. */
  fun shouldReadyArtist(view: ContestResultView): Boolean =
      view.won && view.rank == Contest.RANK_MASTER && view.totalPoints >= 800

  /** GiveMonArtistRibbon. */
  suspend fun giveArtistRibbon(view: ContestResultView): Boolean {
    val c = stats.get(view.monId)
    if (c.has(Ribbon.ARTIST) || !shouldReadyArtist(view)) return false
    stats.set(view.monId, c.copy(ribbons = c.ribbons or Ribbon.ARTIST))
    return true
  }

  /** SaveMuseumContestPainting (CONTEST_SAVE_FOR_MUSEUM). */
  suspend fun saveMuseumPainting(charId: Long, view: ContestResultView) {
    val m = view.engine.mons[view.me]
    repo.savePainting(
        charId,
        ContestPainting(
            view.category,
            view.monId,
            m.dex,
            m.shiny,
            m.nickname,
            m.trainerName,
            view.rank,
            LocalDateTime.now()))
  }

  /** SaveContestWinner for the hall: the last six winners of the player's contests. */
  suspend fun saveHallWinner(charId: Long, view: ContestResultView) {
    val hall = repo.paintings(charId).filter { it.category >= HALL_BASE }.sortedBy { it.createdAt }
    val slot =
        if (hall.size < 6) HALL_BASE + hall.size else hall.first().category // the oldest goes
    val w = view.engine.mons[view.engine.winner]
    repo.savePainting(
        charId,
        ContestPainting(
            slot,
            if (view.won) view.monId else 0,
            w.dex,
            w.shiny,
            w.nickname,
            w.trainerName,
            view.rank,
            LocalDateTime.now()))
  }

  suspend fun paintings(charId: Long) = repo.paintings(charId)

  /**
   * showcontestpainting: [which] is CONTEST_WINNER_* (1..6 hall, 7 artist = the last contest, 8..12
   * museum Cool..Tough). Suspends until the player closes it.
   */
  suspend fun showPainting(session: SessionContext, charId: Long, which: Int): Boolean {
    val p: ContestPainting? =
        when (which) {
          in 1..6 ->
              repo.paintings(charId).firstOrNull { it.category == HALL_BASE + which - 1 }
                  ?: defaultHallWinner(which)
          CONTEST_WINNER_ARTIST ->
              results[charId]?.let { v ->
                val m = v.engine.mons[v.me]
                ContestPainting(v.category, v.monId, m.dex, m.shiny, m.nickname, m.trainerName, v.rank,
                    LocalDateTime.now())
              }
          in CONTEST_WINNER_MUSEUM_COOL..CONTEST_WINNER_MUSEUM_COOL + 4 ->
              repo.paintings(charId).firstOrNull { it.category == which - CONTEST_WINNER_MUSEUM_COOL }
          else -> null
        }
    if (p == null) return false
    val museum = which >= CONTEST_WINNER_MUSEUM_COOL
    val cat = p.category % HALL_BASE
    val caption =
        when {
          which == CONTEST_WINNER_ARTIST -> "" // the artist's painting has no caption
          museum ->
              data.museumCaptions
                  .getOrElse(cat * 3 + (p.pokemonId % 3).toInt()) { "" }
                  .replace("{STR_VAR_1}", p.nickname)
          else ->
              data.hallCaption
                  .replace(
                      "{STR_VAR_1}",
                      "${data.paintingContestNames.getOrElse(cat) { "" }} ${data.paintingRankNames.getOrElse(p.rank) { "" }}")
                  .replace("{STR_VAR_2}", p.trainer)
                  .replace("{STR_VAR_3}", p.nickname)
        }
    docs.send(session, "painting") {
      put("open", true)
      put("dex", p.dex)
      put("shiny", p.shiny)
      put("nickname", p.nickname)
      put("trainer", p.trainer)
      put("category", cat)
      put("rank", p.rank)
      put("museum", museum)
      put("caption", caption)
    }
    docs.await(session, "painting", 5 * 60_000L)
    docs.close(session, "painting")
    return true
  }

  private fun defaultHallWinner(which: Int): ContestPainting? {
    val d = data.defaultWinners.getOrNull(which - 1) ?: return null
    return ContestPainting(
        HALL_BASE + which - 1, 0, d.dex, false, d.nickname, d.trainer, d.rank, LocalDateTime.MIN)
  }

  companion object {
    const val JOIN_WINDOW_MS = 12_000L
    const val MOVE_TIMEOUT_MS = 30_000L
    const val INTRO_MS = 4_000L
    const val EVENT_MS = 900L
    const val RESULTS_MS = 60_000L
    const val REPETITION_NOT_BORING = 3
    const val HALL_BASE = 10
    const val CONTEST_WINNER_ARTIST = 7
    const val CONTEST_WINNER_MUSEUM_COOL = 8
  }
}

internal fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()

internal fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.content?.toLongOrNull()

