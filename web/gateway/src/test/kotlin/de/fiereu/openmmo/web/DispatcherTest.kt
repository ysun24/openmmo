package de.fiereu.openmmo.web

import de.fiereu.openmmo.client.net.MarketSortOrder
import de.fiereu.openmmo.common.enums.Direction
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class DispatcherTest {
  private val calls = mutableListOf<Pair<String, List<Any?>>>()

  /** Records every call; returns null (success) except for battleFight(3), an error. */
  private val actions =
      Proxy.newProxyInstance(javaClass.classLoader, arrayOf(GameActions::class.java)) { _, m, args
        ->
        calls += m.name to (args?.toList() ?: emptyList())
        if (m.name == "battleFight" && args?.get(0) == 3) "no move 4" else null
      } as GameActions

  private fun send(json: String): String? {
    val obj: JsonObject = Json.parseToJsonElement(json).jsonObject
    return Dispatcher.dispatch(obj["t"]!!.toString().trim('"'), obj, actions)
  }

  @Test
  fun `step carries direction, run and seq`() {
    assertNull(send("""{"t":"step","dir":"left","run":true,"seq":41}"""))
    assertNull(send("""{"t":"step","dir":"down","seq":42}"""))
    assertEquals<List<Pair<String, List<Any?>>>>(
        listOf(
            "step" to listOf<Any?>(Direction.LEFT, true, 41),
            "step" to listOf<Any?>(Direction.DOWN, false, 42)),
        calls)
  }

  @Test
  fun `bad fields are errors, not calls`() {
    assertEquals("missing or bad field: dir", send("""{"t":"step","dir":"north","seq":1}"""))
    assertEquals("missing or bad field: seq", send("""{"t":"step","dir":"up"}"""))
    assertEquals("missing or bad field: seq", send("""{"t":"step","dir":"up","seq":"1"}"""))
    assertEquals("move out of range", send("""{"t":"battle.fight","move":7}"""))
    assertEquals("missing or bad field: party", send("""{"t":"duel","name":"Gary","party":9}"""))
    assertEquals(
        "missing or bad field: levelCap", send("""{"t":"duel","name":"Gary","levelCap":101}"""))
    assertEquals("qty out of range", send("""{"t":"shop.buy","item":5017,"qty":0}"""))
    assertEquals("empty text", send("""{"t":"say","text":"   "}"""))
    assertEquals("text too long", send("""{"t":"say","text":"${"x".repeat(300)}"}"""))
    assertEquals("unknown message type: fly", send("""{"t":"fly"}"""))
    assertEquals("bad sort: cheap", send("""{"t":"market.search","sort":"cheap"}"""))
    assertTrue(calls.isEmpty(), "$calls")
  }

  @Test
  fun `client api errors are passed back`() {
    assertEquals("no move 4", send("""{"t":"battle.fight","move":3}"""))
    assertNull(send("""{"t":"battle.fight","move":0}"""))
  }

  @Test
  fun `every simple message reaches its action`() {
    val cases =
        mapOf(
            """{"t":"face","dir":"right"}""" to ("face" to listOf<Any?>(Direction.RIGHT)),
            """{"t":"say","text":" /testbattle 19 3 "}""" to ("say" to listOf("/testbattle 19 3")),
            """{"t":"a"}""" to ("a" to emptyList()),
            """{"t":"b"}""" to ("b" to emptyList()),
            """{"t":"cursor","delta":-1}""" to ("cursor" to listOf(-1)),
            """{"t":"cursor","delta":5}""" to ("cursor" to listOf(1)),
            """{"t":"choose","index":1}""" to ("choose" to listOf(1)),
            """{"t":"shop.buy","item":5017,"qty":2}""" to ("shopBuy" to listOf(5017, 2)),
            """{"t":"shop.sell","item":5017,"qty":1}""" to ("shopSell" to listOf(5017, 1)),
            """{"t":"shop.close"}""" to ("shopClose" to emptyList()),
            """{"t":"item.use","item":5017,"mon":"2412313315698249729"}""" to
                ("itemUse" to listOf<Any?>(5017, 2412313315698249729L)),
            """{"t":"item.use","item":5079}""" to ("itemUse" to listOf(5079, null)),
            """{"t":"party.move","from":0,"to":2}""" to ("partyMove" to listOf(0, 2)),
            """{"t":"pc.deposit","slot":1}""" to ("pcDeposit" to listOf(1)),
            """{"t":"pc.withdraw","slot":37}""" to ("pcWithdraw" to listOf(37)),
            """{"t":"battle.switch","slot":2}""" to ("battleSwitch" to listOf(2)),
            """{"t":"battle.run"}""" to ("battleRun" to emptyList()),
            """{"t":"battle.ball"}""" to ("battleBall" to emptyList()),
            """{"t":"battle.learn","slot":null}""" to ("battleLearn" to listOf(null)),
            """{"t":"battle.learn"}""" to ("battleLearn" to listOf(null)),
            """{"t":"battle.learn","slot":2}""" to ("battleLearn" to listOf(2)),
            """{"t":"battle.close"}""" to ("battleClose" to emptyList()),
            """{"t":"duel","name":"Gary"}""" to ("duel" to listOf("Gary")),
            """{"t":"duel","name":"Gary","levelCap":50,"party":3,"timer":30}""" to
                ("duelRules" to listOf("Gary", 50, 3, 30)),
            """{"t":"duel","name":"Gary","timer":0}""" to ("duelRules" to listOf("Gary", 0, 6, 0)),
            """{"t":"duel.answer","accept":false}""" to ("duelAnswer" to listOf(false)),
            // r3-battle
            """{"t":"battle.fight","move":1,"pos":1,"target":{"side":"foe","pos":0}}""" to
                ("battleFightAt" to listOf<Any?>(1, 1, "foe", 0)),
            """{"t":"battle.item","item":5017,"mon":"2412313315698249729"}""" to
                ("battleItem" to listOf<Any?>(5017, 2412313315698249729L, null, null)),
            """{"t":"battle.item","item":5038,"mon":"5","moveSlot":2,"pos":1}""" to
                ("battleItem" to listOf<Any?>(5038, 5L, 2, 1)),
            """{"t":"battle.ball","item":5003}""" to ("battleBallItem" to listOf(5003)),
            """{"t":"evolution.answer","proceed":false}""" to ("evolutionAnswer" to listOf(false)),
            """{"t":"evolution.close"}""" to ("evolutionClose" to emptyList()),
            """{"t":"duel","name":"Gary","party":2,"doubles":true}""" to
                ("duelRulesDoubles" to listOf<Any?>("Gary", 0, 2, 0, true)),
            """{"t":"trade.request","name":"Gary"}""" to ("tradeRequest" to listOf("Gary")),
            """{"t":"trade.accept"}""" to ("tradeAction" to listOf("accept")),
            """{"t":"trade.decline"}""" to ("tradeAction" to listOf("decline")),
            """{"t":"trade.ready"}""" to ("tradeAction" to listOf("ready")),
            """{"t":"trade.confirm"}""" to ("tradeAction" to listOf("confirm")),
            """{"t":"trade.cancel"}""" to ("tradeAction" to listOf("cancel")),
            """{"t":"trade.mon","slot":1}""" to ("tradeMon" to listOf(1)),
            """{"t":"trade.item","item":5017,"qty":0}""" to ("tradeItem" to listOf(5017, 0)),
            """{"t":"trade.money","amount":500}""" to ("tradeMoney" to listOf(500)),
            """{"t":"market.search"}""" to
                ("marketSearch" to listOf(true, null, null, null, MarketSortOrder.PRICE_ASC, 0)),
            """{"t":"market.search","kind":"item","id":5017,"min":10,"max":99,"sort":"new","page":2}""" to
                ("marketSearch" to listOf(false, 5017, 10, 99, MarketSortOrder.NEWEST, 2)),
            """{"t":"market.search","kind":null,"sort":"price-desc"}""" to
                ("marketSearch" to listOf(true, null, null, null, MarketSortOrder.PRICE_DESC, 0)),
            """{"t":"market.mine"}""" to ("marketMine" to emptyList()),
            """{"t":"market.inventory"}""" to ("marketInventory" to emptyList()),
            """{"t":"market.listMon","slot":1,"price":1500}""" to
                ("marketListMon" to listOf(1, 1500)),
            """{"t":"market.listItem","item":5017,"qty":2,"price":150}""" to
                ("marketListItem" to listOf(5017, 2, 150)),
            """{"t":"market.buy","id":"2412313315698249728"}""" to
                ("marketBuy" to listOf(2412313315698249728L)),
            """{"t":"market.cancel","id":7}""" to ("marketCancel" to listOf(7L)),
        )
    for ((json, expected) in cases) {
      calls.clear()
      assertNull(send(json), json)
      assertEquals(listOf(expected), calls, json)
    }
  }
}
