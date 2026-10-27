package de.fiereu.openmmo.server.game.minigame.link

import de.fiereu.network.SessionContext
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.server.game.minigame.MinigameChannel
import de.fiereu.openmmo.server.game.minigame.MinigameRewards
import de.fiereu.openmmo.server.game.minigame.MinigameSave
import de.fiereu.openmmo.server.game.minigame.MinigameTopic
import de.fiereu.openmmo.server.game.minigame.action
import de.fiereu.openmmo.server.game.minigame.int
import de.fiereu.openmmo.server.game.minigame.jsonOf
import de.fiereu.openmmo.server.game.minigame.str
import de.fiereu.openmmo.server.game.session.PlayerState
import de.fiereu.openmmo.server.game.session.SessionRegistry
import de.fiereu.openmmo.server.game.storage.CharacterStore
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private val log = KotlinLogging.logger {}

/**
 * The wireless minigames for online players (topics "lobby", "berrycrush", "dodrio", "pokejump"):
 * a player hosts a group (TryBecomeLinkLeader), others join it (TryJoinLinkGroup), the leader
 * starts it; empty slots up to the game's minimum are filled with NPC players, so one player can
 * play alone. The games run on the server at 60 frames a second.
 */
@Singleton
class LinkGameService
@Inject
constructor(
    private val sessions: SessionRegistry,
    private val characters: CharacterStore,
    private val items: ItemRegistry,
    private val save: MinigameSave,
    private val rewards: MinigameRewards,
    private val scope: CoroutineScope,
) : MinigameTopic {
  override val topics = setOf("lobby", "berrycrush", "dodrio", "pokejump")

  enum class Game(val id: String, val title: String, val min: Int, val max: Int) {
    BERRY_CRUSH("berrycrush", "BERRY CRUSH", 2, 5),
    DODRIO("dodrio", "DODRIO BERRY PICKING", 3, 5),
    JUMP("pokejump", "POKéMON JUMP", 2, 5),
  }

  inner class Group(val id: Int, val game: Game, val leader: Long) {
    val members = java.util.concurrent.CopyOnWriteArrayList<Long>(listOf(leader))
    /** Berry index (Berry Crush) / chosen dex per member. */
    val picks = ConcurrentHashMap<Long, Int>()
    @Volatile var job: Job? = null
    @Volatile var running = false
    @Volatile var results: JsonObject? = null
  }

  private val ids = AtomicInteger()
  private val groups = ConcurrentHashMap<Int, Group>()
  private val groupOf = ConcurrentHashMap<Long, Int>()
  private val inputs = ConcurrentHashMap<Long, (JsonObject) -> Unit>()

  private fun name(charId: Long) = characters.getCharacter(charId)?.info?.name ?: "?"

  private fun party(charId: Long) = characters.getCharacter(charId)?.pokemon ?: emptyList()

  // --- lobby ----------------------------------------------------------------------------------

  private fun lobbyJson(charId: Long): JsonObject {
    val mine = groupOf[charId]?.let { groups[it] }
    return jsonOf(
        "groups" to
            groups.values.filter { !it.running }.map { g ->
              jsonOf("id" to g.id, "game" to g.game.id, "title" to g.game.title, "leader" to name(g.leader),
                  "players" to g.members.map(::name), "max" to g.game.max)
            },
        "mine" to
            mine?.let { g ->
              jsonOf("id" to g.id, "game" to g.game.id, "title" to g.game.title, "leader" to (g.leader == charId),
                  "players" to g.members.map(::name), "min" to g.game.min, "max" to g.game.max,
                  "running" to g.running, "results" to g.results)
            },
        "games" to Game.entries.map { jsonOf("id" to it.id, "title" to it.title, "min" to it.min, "max" to it.max) },
        "berries" to ownedBerries(charId),
    )
  }

  /** Berry index -> count of the berries in the bag (for Berry Crush). */
  private fun ownedBerries(charId: Long): List<JsonObject> {
    val bag = characters.getCharacter(charId)?.items ?: return emptyList()
    return BERRY_NAMES.mapIndexedNotNull { i, n ->
      val id = berryItemId(n) ?: return@mapIndexedNotNull null
      val q = bag[id] ?: 0
      if (q > 0) jsonOf("berry" to i, "name" to "$n BERRY", "qty" to q) else null
    }
  }

  private val berryIds = ConcurrentHashMap<String, Int>()

  fun berryItemId(name: String): Int? =
      berryIds[name]
          ?: items.all().firstOrNull { it.name.equals("$name Berry", true) }?.let { items.idOrNull(it) }
              ?.also { berryIds[name] = it }

  private fun pushLobby(charId: Long) = sessions.getByCharacterId(charId)?.let {
    MinigameChannel.send(it, "lobby", lobbyJson(charId))
  }

  private fun pushAllLobbies() = sessions.onlineCharacterIds().forEach { if (watchers.contains(it)) pushLobby(it) }

  private val watchers = ConcurrentHashMap.newKeySet<Long>()

  private fun leave(charId: Long) {
    val gid = groupOf.remove(charId) ?: return
    val g = groups[gid] ?: return
    g.members.remove(charId)
    inputs.remove(charId)
    if (g.members.isEmpty() || (g.leader == charId && !g.running)) {
      groups.remove(gid)
      g.job?.cancel()
      g.members.forEach { groupOf.remove(it); pushLobby(it) }
    }
  }

  /** Opens the lobby for [game] (the Wireless Club / Joyful Game Corner attendants). */
  fun openLobby(session: SessionContext, charId: Long, game: String? = null) {
    watchers += charId
    MinigameChannel.send(session, "lobby", JsonObject(lobbyJson(charId) + ("focus" to JsonPrimitive(game ?: ""))))
  }

  override suspend fun onAction(session: SessionContext, state: PlayerState, topic: String, body: JsonObject) {
    val charId = state.characterId ?: return
    if (topic != "lobby") {
      inputs[charId]?.invoke(body)
      return
    }
    when (body.action()) {
      "open" -> watchers += charId
      "close" -> {
        watchers -= charId
        if (groupOf[charId]?.let { groups[it]?.running } != true) leave(charId)
        MinigameChannel.close(session, "lobby")
        pushAllLobbies()
        return
      }
      "host" -> {
        val game = Game.entries.firstOrNull { it.id == body.str("game") } ?: return
        leave(charId)
        val g = Group(ids.incrementAndGet(), game, charId)
        groups[g.id] = g
        groupOf[charId] = g.id
        watchers += charId
      }
      "join" -> {
        val g = groups[body.int("id") ?: return] ?: return
        if (g.running || g.members.size >= g.game.max) return
        leave(charId)
        g.members += charId
        groupOf[charId] = g.id
        watchers += charId
      }
      "leave" -> leave(charId)
      "pick" -> {
        val g = groupOf[charId]?.let { groups[it] } ?: return
        g.picks[charId] = body.int("value") ?: return
      }
      "start" -> {
        val g = groupOf[charId]?.let { groups[it] } ?: return
        if (g.leader != charId || g.running) return
        val err = startGame(g)
        if (err != null) rewards.tell(session, err)
      }
      else -> return
    }
    pushAllLobbies()
    pushLobby(charId)
  }

  // --- games ----------------------------------------------------------------------------------

  private val NPC_NAMES = listOf("JOEY", "LIZ", "OSCAR", "MIA", "BEN")

  /** ChooseMonForWirelessMinigame: the first eligible party Pokémon (or the picked one). */
  private fun monFor(g: Group, charId: Long): Int? {
    val dexes = party(charId).map { it.dexId.toInt() }
    val picked = g.picks[charId]?.takeIf { it in dexes }
    return when (g.game) {
      Game.BERRY_CRUSH -> dexes.firstOrNull() ?: 25
      Game.DODRIO -> if (85 in dexes) 85 else null // IsDodrioInParty
      Game.JUMP -> picked?.takeIf { it in PokemonJump.SPECIES } ?: dexes.firstOrNull { it in PokemonJump.SPECIES }
    }
  }

  private fun startGame(g: Group): String? {
    val humans = g.members.toList()
    val players = ArrayList<LinkPlayer>()
    for (c in humans) {
      val dex = monFor(g, c)
          ?: return when (g.game) {
            Game.DODRIO -> "${name(c)} needs a DODRIO in the party."
            else -> "${name(c)} has no POKéMON that can play POKéMON JUMP."
          }
      players += LinkPlayer(name(c), c, dex)
    }
    if (g.game == Game.BERRY_CRUSH) {
      for (c in humans) {
        val b = g.picks[c] ?: return "${name(c)} must choose a berry."
        val id = berryItemId(BERRY_NAMES.getOrNull(b) ?: return "bad berry") ?: return "bad berry"
        if ((characters.getCharacter(c)?.items?.get(id) ?: 0) <= 0) return "${name(c)} has no ${BERRY_NAMES[b]} BERRY."
      }
    }
    var n = 0
    val npcDex = when (g.game) { Game.DODRIO -> 85; Game.JUMP -> 25; else -> 25 }
    while (players.size < g.game.min.coerceAtLeast(if (g.game == Game.JUMP && humans.size == 1) 3 else 0)) {
      players += LinkPlayer(NPC_NAMES[n++ % NPC_NAMES.size], null, if (g.game == Game.JUMP) listOf(25, 4, 7, 1)[n % 4] else npcDex)
    }
    g.running = true
    g.results = null
    g.job = scope.launch { runGame(g, players) }
    return null
  }

  private suspend fun runGame(g: Group, players: List<LinkPlayer>) {
    try {
      when (g.game) {
        Game.BERRY_CRUSH -> runCrush(g, players)
        Game.DODRIO -> runDodrio(g, players)
        Game.JUMP -> runJump(g, players)
      }
    } catch (e: Exception) {
      if (e !is kotlinx.coroutines.CancellationException) log.warn(e) { "link game ${g.game} failed" }
    } finally {
      g.running = false
      players.mapNotNull { it.charId }.forEach { inputs.remove(it) }
      pushAllLobbies()
      g.members.forEach(::pushLobby)
    }
  }

  private fun broadcast(players: List<LinkPlayer>, topic: String, body: JsonObject) =
      players.forEach { p -> p.charId?.let { c -> sessions.getByCharacterId(c)?.let { MinigameChannel.send(it, topic, body) } } }

  /** Runs [tick] at 60 frames a second, sending [state] every third frame, until [done]. */
  private suspend fun frames(done: () -> Boolean, tick: () -> Unit, push: () -> Unit) {
    var last = System.nanoTime()
    var frameDebt = 0.0
    var f = 0
    while (scope.isActive && !done()) {
      delay(16)
      val now = System.nanoTime()
      frameDebt += (now - last) / 16_666_667.0
      last = now
      while (frameDebt >= 1 && !done()) {
        tick()
        frameDebt -= 1
        if (++f % 3 == 0) push()
      }
    }
    push()
  }

  private suspend fun countdown(players: List<LinkPlayer>, topic: String, base: JsonObject) {
    for (i in 3 downTo 1) {
      broadcast(players, topic, JsonObject(base + ("countdown" to JsonPrimitive(i))))
      delay(800)
    }
  }

  private fun playersJson(players: List<LinkPlayer>) =
      players.map { jsonOf("name" to it.name, "dex" to it.dex, "npc" to it.npc) }

  private suspend fun runCrush(g: Group, players: List<LinkPlayer>) {
    val rnd = kotlin.random.Random.Default
    val berries = players.map { p -> p.charId?.let { g.picks[it] } ?: rnd.nextInt(0, 20) }
    // The berries are used up (the GBA takes them from the bag when they are dropped in).
    for ((i, p) in players.withIndex()) p.charId?.let { c ->
      sessions.getByCharacterId(c)?.let { s -> rewards.addItem(s, c, berryItemId(BERRY_NAMES[berries[i]])!!, -1) }
    }
    val game = BerryCrush(players, berries)
    players.forEachIndexed { i, p -> p.charId?.let { inputs[it] = { b -> if (b.str("action") == "press") game.press(i) } } }
    val base = jsonOf("players" to playersJson(players), "berries" to berries.map { "${BERRY_NAMES[it]} BERRY" }, "target" to game.target)
    countdown(players, "berrycrush", base)
    val t0 = System.currentTimeMillis()
    frames({ game.done || System.currentTimeMillis() - t0 > 120_000 }, game::tick) {
      broadcast(players, "berrycrush", JsonObject(base + jsonOf("depth" to game.depth, "total" to game.total,
          "presses" to game.presses.toList(), "frame" to game.frame)))
    }
    val powder = game.powder
    val results = jsonOf(
        "game" to "berrycrush", "time" to game.frame, "powder" to powder, "silkiness" to game.silkiness,
        "speed" to game.pressingSpeed / 256.0,
        "rows" to players.mapIndexed { i, p -> jsonOf("name" to p.name, "presses" to game.presses[i], "sync" to game.syncPresses[i], "neat" to game.maxNeat[i]) })
    g.results = results
    for (p in players) p.charId?.let { c ->
      record(c, "berrycrush") { r ->
        r + mapOf("powder" to JsonPrimitive((r["powder"]?.toString()?.toIntOrNull() ?: 0) + powder),
            "best${players.size}" to JsonPrimitive(maxOf(r["best${players.size}"]?.toString()?.toIntOrNull() ?: 0, game.pressingSpeed)))
      }
    }
    broadcast(players, "berrycrush", JsonObject(base + jsonOf("depth" to 32, "total" to game.total, "presses" to game.presses.toList(), "results" to results)))
  }

  private suspend fun runDodrio(g: Group, players: List<LinkPlayer>) {
    val game = DodrioBerryPicking(players)
    val dirs = mapOf("left" to 0, "up" to 1, "right" to 2)
    players.forEachIndexed { i, p ->
      p.charId?.let { inputs[it] = { b -> if (b.str("action") == "pick") dirs[b.str("dir")]?.let { d -> game.pickHead(i, d) } } }
    }
    val base = jsonOf("players" to playersJson(players), "columns" to game.columns)
    countdown(players, "dodrio", base)
    val t0 = System.currentTimeMillis()
    frames({ game.over || System.currentTimeMillis() - t0 > 300_000 }, game::tick) {
      broadcast(players, "dodrio", JsonObject(base + jsonOf(
          "berries" to (0 until game.columns).map { c -> if (game.berryType[c] < 0) null else jsonOf("t" to game.berryType[c], "d" to game.fallDist[c]) },
          "picks" to game.pick.toList(), "scores" to players.indices.map(game::score), "gray" to game.graySquares)))
    }
    val prize = game.prize()
    val results = jsonOf("game" to "dodrio", "prize" to prize?.let { "$it BERRY" },
        "rows" to players.mapIndexed { i, p -> jsonOf("name" to p.name, "score" to game.score(i), "blue" to game.picked[i][0], "green" to game.picked[i][1], "gold" to game.picked[i][2], "missed" to game.picked[i][3]) })
    g.results = results
    for ((i, p) in players.withIndex()) p.charId?.let { c ->
      if (prize != null) sessions.getByCharacterId(c)?.let { s -> berryItemId(prize)?.let { rewards.addItem(s, c, it, 1) } }
      record(c, "dodrio") { r ->
        r + mapOf("bestScore" to JsonPrimitive(maxOf(r["bestScore"]?.toString()?.toIntOrNull() ?: 0, game.score(i))),
            "berriesPicked" to JsonPrimitive(maxOf(r["berriesPicked"]?.toString()?.toIntOrNull() ?: 0, game.berriesPicked(i))))
      }
    }
    broadcast(players, "dodrio", JsonObject(base + jsonOf("results" to results)))
  }

  private suspend fun runJump(g: Group, players: List<LinkPlayer>) {
    val game = PokemonJump(players, players.map { PokemonJump.SPECIES[it.dex] ?: 1 })
    players.forEachIndexed { i, p -> p.charId?.let { inputs[it] = { b -> if (b.str("action") == "jump") game.jump(i) } } }
    val base = jsonOf("players" to playersJson(players))
    countdown(players, "pokejump", base)
    frames({ game.over }, game::tick) {
      broadcast(players, "pokejump", JsonObject(base + jsonOf(
          "phase" to game.phase, "period" to game.period, "score" to game.score, "jumps" to game.jumpsInRow,
          "air" to players.indices.map { if (game.inAir(it)) 1 else 0 }, "excellents" to game.excellentsInRow)))
    }
    val qty = game.prizeQuantity()
    val prize = if (qty > 0) game.prizeItem() else null
    val results = jsonOf("game" to "pokejump", "score" to game.score, "jumps" to game.jumpsInRow,
        "excellents" to game.bestExcellents, "tripped" to players.getOrNull(game.trippedBy)?.name,
        "prize" to prize?.let { "$it BERRY" }, "qty" to qty)
    g.results = results
    for (p in players) p.charId?.let { c ->
      if (prize != null) sessions.getByCharacterId(c)?.let { s -> berryItemId(prize)?.let { rewards.addItem(s, c, it, qty) } }
      record(c, "pokejump") { r ->
        r + mapOf("bestScore" to JsonPrimitive(maxOf(r["bestScore"]?.toString()?.toIntOrNull() ?: 0, game.score)),
            "jumpsInRow" to JsonPrimitive(maxOf(r["jumpsInRow"]?.toString()?.toIntOrNull() ?: 0, game.jumpsInRow)),
            "excellentsInRow" to JsonPrimitive(maxOf(r["excellentsInRow"]?.toString()?.toIntOrNull() ?: 0, game.bestExcellents)))
      }
    }
    broadcast(players, "pokejump", JsonObject(base + jsonOf("results" to results)))
  }

  /** The minigame records (save section "records"), kept per game. */
  private suspend fun record(charId: Long, key: String, change: (JsonObject) -> Map<String, kotlinx.serialization.json.JsonElement>) {
    val all = save.section(charId, "records")
    val mine = all[key] as? JsonObject ?: JsonObject(emptyMap())
    save.setSection(charId, "records", JsonObject(all + (key to JsonObject(change(mine)))))
  }

  suspend fun records(charId: Long): JsonObject = save.section(charId, "records")

  override fun onDisconnect(charId: Long) {
    watchers -= charId
    leave(charId)
  }

  /** A Union Room minigame invite was accepted: [leader] hosts [game], [other] joins. */
  fun inviteTogether(leader: Long, other: Long, game: String) {
    val kind = Game.entries.firstOrNull { it.id == game } ?: return
    leave(leader)
    leave(other)
    val g = Group(ids.incrementAndGet(), kind, leader)
    g.members += other
    groups[g.id] = g
    groupOf[leader] = g.id
    groupOf[other] = g.id
    watchers += leader
    watchers += other
    pushLobby(leader)
    pushLobby(other)
  }

  /** The players in [charId]'s group, for the specials. */
  fun groupSize(charId: Long): Int = groupOf[charId]?.let { groups[it]?.members?.size } ?: 0

  fun buildRecords(r: JsonObject) = buildJsonObject { r.forEach { (k, v) -> put(k, v) } }
}
