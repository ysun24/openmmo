package de.fiereu.openmmo.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * r5-minigames (PROTOCOL.md section "r5-minigames"). The server sends each minigame topic as a JSON
 * object (s2c 0xDD) and the gateway passes it through as `state.minigames.<topic>`; the browser's
 * `minigame` message `{topic, ...}` goes to the server as the rest of the object (c2s 0xDC). The
 * server validates every field.
 */
object MinigameJson {
  private val TOPIC = Regex("^[a-z][a-z0-9]{0,23}$")

  /** `state.minigames`: every open topic, or null when none is open. */
  fun section(topics: Map<String, String>): JsonElement {
    if (topics.isEmpty()) return JsonNull
    return buildJsonObject {
      for ((k, v) in topics) {
        val parsed = runCatching { Json.parseToJsonElement(v) }.getOrNull()
        if (parsed is JsonObject) put(k, parsed)
      }
    }
  }

  /** The browser's `minigame` message. Returns an error for the browser, or null. */
  fun action(m: JsonObject, g: GameActions): String? {
    val topic = (m["topic"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (topic == null || !TOPIC.matches(topic)) return "missing or bad field: topic"
    val body = JsonObject(m.filterKeys { it != "t" && it != "topic" && it != "seq" })
    return g.minigame(topic, body.toString())
  }
}
