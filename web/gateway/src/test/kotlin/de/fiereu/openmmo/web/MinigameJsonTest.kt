package de.fiereu.openmmo.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class MinigameJsonTest {
  private class Rec {
    var got: Pair<String, String>? = null
    val actions: GameActions =
        java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(GameActions::class.java)) { _, m, a ->
              if (m.name == "minigame") got = a[0] as String to a[1] as String
              null
            } as GameActions
  }

  @Test
  fun `the action keeps every field but t, topic and seq`() {
    val r = Rec()
    val m =
        Json.parseToJsonElement("""{"t":"minigame","topic":"slots","action":"bet","n":3,"seq":7}""")
            .jsonObject
    assertNull(MinigameJson.action(m, r.actions))
    assertEquals("slots", r.got!!.first)
    assertEquals(
        Json.parseToJsonElement("""{"action":"bet","n":3}"""),
        Json.parseToJsonElement(r.got!!.second))
  }

  @Test
  fun `a bad topic is refused`() {
    val m = Json.parseToJsonElement("""{"t":"minigame","topic":"Bad Topic!"}""").jsonObject
    assertEquals("missing or bad field: topic", MinigameJson.action(m, Rec().actions))
  }

  @Test
  fun `the section parses each topic and is null when none is open`() {
    assertEquals(JsonNull, MinigameJson.section(emptyMap()))
    val s =
        MinigameJson.section(mapOf("coins" to """{"coins":5}""", "bad" to "not json")) as JsonObject
    assertEquals(setOf("coins"), s.keys)
  }
}
