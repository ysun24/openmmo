package de.fiereu.openmmo.server.game.minigame

import de.fiereu.network.PacketEvent
import de.fiereu.network.SessionContext
import de.fiereu.openmmo.net.game.packets.minigame.MINIGAME_BODY_MAX
import de.fiereu.openmmo.net.game.packets.minigame.MinigameActionPacket
import de.fiereu.openmmo.net.game.packets.minigame.MinigameStatePacket
import de.fiereu.openmmo.server.game.session.PLAYER_STATE
import de.fiereu.openmmo.server.game.session.PlayerState
import de.fiereu.openmmo.server.game.session.SessionRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

private val log = KotlinLogging.logger {}

/**
 * One minigame topic's server side ("slots", "roulette", "lobby", ...). Bound into a set by
 * [MinigameModule]; [MinigameChannel] routes every c2s 0xDC action to the handler of its topic.
 */
interface MinigameTopic {
  val topics: Set<String>

  suspend fun onAction(session: SessionContext, state: PlayerState, topic: String, body: JsonObject)

  /** The character went offline. */
  fun onDisconnect(charId: Long) {}
}

/** The c2s 0xDC / s2c 0xDD channel (PROTOCOL.md section "r5-minigames"). */
@Singleton
class MinigameChannel
@Inject
constructor(
    handlers: Set<@JvmSuppressWildcards MinigameTopic>,
    private val sessions: SessionRegistry,
) {
  private val byTopic: Map<String, MinigameTopic> = buildMap {
    for (h in handlers) for (t in h.topics) check(put(t, h) == null) { "topic $t claimed twice" }
  }
  private val all = handlers.toList()

  suspend fun onAction(event: PacketEvent<MinigameActionPacket>) {
    val session = event.session
    val state = session.attributes[PLAYER_STATE] ?: return
    if (state.characterId == null) return
    val p = event.packet
    val handler = byTopic[p.topic] ?: return log.debug { "unknown minigame topic ${p.topic}" }
    val body =
        runCatching { Json.parseToJsonElement(p.body) as JsonObject }.getOrNull()
            ?: return log.debug { "bad minigame body for ${p.topic}" }
    try {
      handler.onAction(session, state, p.topic, body)
    } catch (e: IllegalArgumentException) {
      log.debug { "minigame ${p.topic} refused: ${e.message}" }
    }
  }

  fun onDisconnect(charId: Long) = all.forEach { it.onDisconnect(charId) }

  companion object {
    /** Sends [topic]'s state; an empty object closes the topic. */
    fun send(session: SessionContext, topic: String, body: JsonObject) {
      val text = body.toString()
      if (text.toByteArray(Charsets.UTF_8).size > MINIGAME_BODY_MAX) {
        log.warn { "minigame $topic state too large (${text.length})" }
        return
      }
      session.send(MinigameStatePacket(topic, text))
    }

    fun close(session: SessionContext, topic: String) = send(session, topic, JsonObject(emptyMap()))
  }

  /** Sends to a character if it is online. */
  fun sendTo(charId: Long, topic: String, body: JsonObject) {
    sessions.getByCharacterId(charId)?.let { send(it, topic, body) }
  }
}

// --- JSON field readers shared by the topics. A bad field is an IllegalArgumentException, which
// the channel logs and drops.

fun JsonObject.str(k: String): String? =
    (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

fun JsonObject.int(k: String): Int? =
    (this[k] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.intOrNull

fun JsonObject.long(k: String): Long? =
    (this[k] as? JsonPrimitive)?.let {
      if (it.isString) it.content.toLongOrNull() else it.longOrNull
    }

fun JsonObject.bool(k: String): Boolean? =
    (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject

fun JsonObject.arr(k: String): JsonArray? = this[k] as? JsonArray

fun JsonObject.reqStr(k: String) = requireNotNull(str(k)) { "missing $k" }

fun JsonObject.reqInt(k: String, range: IntRange? = null): Int {
  val v = requireNotNull(int(k)) { "missing $k" }
  require(range == null || v in range) { "$k out of range" }
  return v
}

fun JsonObject.action(): String = reqStr("action")

fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject =
    JsonObject(pairs.associate { (k, v) -> k to toJson(v) })

fun toJson(v: Any?): JsonElement =
    when (v) {
      null -> JsonNull
      is JsonElement -> v
      is String -> JsonPrimitive(v)
      is Number -> JsonPrimitive(v)
      is Boolean -> JsonPrimitive(v)
      is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toJson(x) })
      is Iterable<*> -> JsonArray(v.map(::toJson))
      is IntArray -> JsonArray(v.map { JsonPrimitive(it) })
      is Array<*> -> JsonArray(v.map(::toJson))
      else -> JsonPrimitive(v.toString())
    }
