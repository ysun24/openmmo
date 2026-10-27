package de.fiereu.openmmo.server.game.minigame.link

import de.fiereu.network.SessionContext
import de.fiereu.openmmo.maps.MapManager
import de.fiereu.openmmo.server.game.minigame.MinigameChannel
import de.fiereu.openmmo.server.game.minigame.MinigameTopic
import de.fiereu.openmmo.server.game.minigame.action
import de.fiereu.openmmo.server.game.minigame.jsonOf
import de.fiereu.openmmo.server.game.minigame.str
import de.fiereu.openmmo.server.game.session.PLAYER_STATE
import de.fiereu.openmmo.server.game.session.PlayerState
import de.fiereu.openmmo.server.game.session.SessionRegistry
import de.fiereu.openmmo.server.game.storage.CharacterStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject

/**
 * The UNION ROOM (union_room.c) for online players, topic "union": everyone who entered it (the
 * Pokémon Center 2F attendant, or the Link menu) and everyone standing in a Pokémon Center 2F is
 * listed; any of them can be asked to trade, battle, chat or play a minigame. An accepted trade or
 * battle request is carried out by the existing trade / duel flow (the asking browser sends the
 * trade.request / duel itself); a minigame invite puts both into one group.
 */
@Singleton
class UnionRoomService
@Inject
constructor(
    private val sessions: SessionRegistry,
    private val characters: CharacterStore,
    private val maps: MapManager,
    private val links: LinkGameService,
) : MinigameTopic {
  override val topics = setOf("union")

  private val inRoom = ConcurrentHashMap.newKeySet<Long>()
  private val chat = CopyOnWriteArrayList<JsonObject>()
  /** target -> (from, what) */
  private val invites = ConcurrentHashMap<Long, Pair<Long, String>>()
  /** inviter -> what the target accepted (the inviter's browser then acts on it) */
  private val accepted = ConcurrentHashMap<Long, Triple<String, String, Int>>()
  private val seq = java.util.concurrent.atomic.AtomicInteger()

  private fun name(c: Long) = characters.getCharacter(c)?.info?.name ?: "?"

  private fun stateOf(c: Long): PlayerState? =
      sessions.getByCharacterId(c)?.attributes?.get(PLAYER_STATE)

  /** The Pokémon Center 2F maps (and the Union Room itself) count as being in the room. */
  private fun inPc2f(s: PlayerState): Boolean {
    val n = maps.getMap(s.regionId, s.bankId, s.mapId)?.name ?: return false
    return n.endsWith("PokemonCenter_2F") || n.startsWith("UnionRoom")
  }

  fun visible(): List<Long> =
      sessions.onlineCharacterIds().filter { c -> c in inRoom || stateOf(c)?.let(::inPc2f) == true }

  fun json(me: Long): JsonObject =
      jsonOf(
          "inRoom" to (me in inRoom),
          "players" to
              visible()
                  .filter { it != me }
                  .map { c ->
                    val s = stateOf(c)
                    jsonOf(
                        "name" to name(c),
                        "where" to
                            (s?.let { maps.getMap(it.regionId, it.bankId, it.mapId)?.name } ?: ""),
                        "region" to (s?.regionId ?: 0),
                        "busy" to (invites.containsKey(c)))
                  },
          "chat" to chat.takeLast(30),
          "invite" to
              invites[me]?.let { (from, what) -> jsonOf("from" to name(from), "what" to what) },
          // kept (with a seq the browser acts on once), so a coalesced state push cannot lose it
          "accepted" to
              accepted[me]?.let { (who, what, n) ->
                jsonOf("name" to who, "what" to what, "seq" to n)
              },
      )

  fun push(c: Long) =
      sessions.getByCharacterId(c)?.let { MinigameChannel.send(it, "union", json(c)) }

  /** Only players with the Union Room open get its updates. */
  private fun pushAll() = inRoom.forEach(::push)

  /** InitUnionRoom / RunUnionRoom: [charId] enters the room. */
  fun enter(charId: Long) {
    inRoom += charId
    pushAll()
  }

  private fun byName(n: String): Long? =
      sessions.onlineCharacterIds().firstOrNull { name(it).equals(n, true) }

  override suspend fun onAction(
      session: SessionContext,
      state: PlayerState,
      topic: String,
      body: JsonObject
  ) {
    val me = state.characterId ?: return
    when (body.action()) {
      "enter" -> inRoom += me
      "leave" -> {
        inRoom -= me
        MinigameChannel.close(session, "union")
        pushAll()
        return
      }
      "chat" -> {
        val text = body.str("text")?.trim()?.take(60) ?: return
        if (text.isEmpty()) return
        chat += jsonOf("name" to name(me), "text" to text)
        while (chat.size > 60) chat.removeAt(0)
      }
      "invite" -> {
        val target = byName(body.str("name") ?: return) ?: return
        val what = body.str("what")?.takeIf { it in WHAT } ?: return
        if (target == me || target !in visible()) return
        invites[target] = me to what
        push(target)
      }
      "answer" -> {
        val (from, what) = invites.remove(me) ?: return
        val yes = body.str("answer") == "yes"
        if (yes) {
          accepted[from] = Triple(name(me), what, seq.incrementAndGet())
          when {
            what.startsWith("game:") -> links.inviteTogether(from, me, what.removePrefix("game:"))
            what == "chat" ->
                chat +=
                    jsonOf(
                        "name" to "UNION ROOM",
                        "text" to "${name(from)} and ${name(me)} started chatting.")
          }
        } else
            chat +=
                jsonOf(
                    "name" to "UNION ROOM", "text" to "${name(me)} declined ${name(from)}'s offer.")
        push(from)
      }
      "get" -> {}
      else -> return
    }
    pushAll()
    push(me)
  }

  override fun onDisconnect(charId: Long) {
    inRoom -= charId
    invites.remove(charId)
    invites.entries.removeIf { it.value.first == charId }
    pushAll()
  }

  companion object {
    val WHAT = setOf("trade", "battle", "chat", "game:berrycrush", "game:dodrio", "game:pokejump")
  }
}
