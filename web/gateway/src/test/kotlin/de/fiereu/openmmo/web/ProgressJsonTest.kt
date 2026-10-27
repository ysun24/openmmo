package de.fiereu.openmmo.web

import de.fiereu.openmmo.client.net.BattleState
import de.fiereu.openmmo.client.net.MarketClient
import de.fiereu.openmmo.client.net.NoticeKind
import de.fiereu.openmmo.client.world.WorldState
import de.fiereu.openmmo.net.game.packets.progress.DaycareView
import de.fiereu.openmmo.net.game.packets.progress.DecorStack
import de.fiereu.openmmo.net.game.packets.progress.ProgressStatePacket
import de.fiereu.openmmo.net.game.packets.trade.TradePhase
import de.fiereu.openmmo.net.game.packets.trade.TradeStatePacket
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ProgressJsonTest {
  private val names =
      object : Names {
        override fun species(dex: Int) = "MON$dex"

        override fun move(id: Int) = "MOVE$id"

        override fun maxPp(id: Int) = 10

        override fun item(id: Int) = "ITEM$id"

        override fun mapName(key: de.fiereu.openmmo.client.world.MapKey) = null

        override fun maxHp(mon: de.fiereu.openmmo.common.Pokemon) = 20

        override fun dexEntry(dex: Int, region: Int) =
            DexEntry("SEED", 7, 69, "A strange seed was planted on its back at birth.")
      }

  private fun packet(region: Int = 0) =
      ProgressStatePacket(
          seen = ProgressStatePacket.bits(listOf(1, 4, 7)),
          caught = ProgressStatePacket.bits(listOf(1)),
          badges = 0b101 or (1 shl 8),
          playSeconds = 3725,
          registeredItem = 0,
          extras = emptyList(),
          daycare = DaycareView(0, emptyList(), emptyList(), emptyList(), false),
          decorations = listOf(DecorStack(1, 2)),
          decorShop = emptyList(),
          learn = null,
          permissions = 0x308,
          region = region,
      )

  @Test
  fun `dex lists seen species, pages only for caught ones`() {
    val d = ProgressJson(names).dex(packet()).jsonObject
    assertEquals(3, d["seen"]!!.jsonPrimitive.int)
    assertEquals(1, d["caught"]!!.jsonPrimitive.int)
    assertEquals(151, d["regionalSize"]!!.jsonPrimitive.int)
    val entries = d["entries"]!!.jsonArray.map { it.jsonObject }
    assertEquals(listOf(1, 4, 7), entries.map { it["dex"]!!.jsonPrimitive.int })
    assertEquals("SEED", entries[0]["category"]!!.jsonPrimitive.content)
    assertEquals(0.7, entries[0]["heightM"]!!.jsonPrimitive.content.toDouble())
    assertNull(entries[1]["text"])
  }

  @Test
  fun `trainer card has both badge rows and the admin bit`() {
    val t = ProgressJson(names).trainer(sources(), packet()).jsonObject
    assertEquals(
        "[true,false,true,false,false,false,false,false]",
        t["badges"]!!.jsonObject["kanto"].toString())
    assertEquals(
        "[true,false,false,false,false,false,false,false]",
        t["badges"]!!.jsonObject["hoenn"].toString())
    assertEquals("true", t["admin"].toString())
    assertEquals(3725, t["playSeconds"]!!.jsonPrimitive.int)
  }

  @Test
  fun `decorations name the decomp's decorations`() {
    val d = ProgressJson(names).decor(packet()).jsonObject
    assertEquals("SMALL DESK", d["owned"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
  }

  @Test
  fun `notices are classified`() {
    assertEquals(NoticeKind.PRESENCE, NoticeKind.classify("Player Gary joined the game."))
    assertEquals(NoticeKind.PRESENCE, NoticeKind.classify("A player left the game."))
    assertEquals(NoticeKind.REWARD, NoticeKind.classify("Ash received the BOULDERBADGE!"))
    assertEquals(NoticeKind.ERROR, NoticeKind.classify("It won't have any effect."))
    assertEquals(NoticeKind.SYSTEM, NoticeKind.classify("Server restarts soon", system = true))
    assertEquals(NoticeKind.INFO, NoticeKind.classify("Welcome to OpenMMO!"))
  }

  @Test
  fun `r3-items messages are routed`() {
    val calls = mutableListOf<Pair<String, List<Any?>>>()
    val g =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(GameActions::class.java)) { _, m, a ->
          calls += m.name to (a?.toList() ?: emptyList())
          null
        } as GameActions
    fun send(json: String): String? {
      val o: JsonObject = Json.parseToJsonElement(json).jsonObject
      return Dispatcher.dispatch(o["t"]!!.jsonPrimitive.content, o, g)
    }
    assertNull(send("""{"t":"item.move","item":5038,"mon":"77","slot":2}"""))
    assertNull(send("""{"t":"learn.reply","slot":null}"""))
    assertNull(send("""{"t":"daycare.deposit","mon":"5"}"""))
    assertNull(send("""{"t":"daycare.withdraw","index":1}"""))
    assertNull(send("""{"t":"decor.buy","id":1,"qty":2}"""))
    assertNull(send("""{"t":"item.register","item":5450}"""))
    assertEquals("slot out of range", send("""{"t":"item.move","item":1,"mon":"1","slot":4}"""))
    assertEquals(
        listOf(
            "itemMove" to listOf<Any?>(5038, 77L, 2),
            "learnReply" to listOf<Any?>(null),
            "daycareDeposit" to listOf<Any?>(5L),
            "daycareWithdraw" to listOf<Any?>(1),
            "decorBuy" to listOf<Any?>(1, 2),
            "itemRegister" to listOf<Any?>(5450)),
        calls)
  }

  private fun sources() =
      Sources(
          world = WorldState().snapshot(),
          battle = BattleState(),
          trade = TradeStatePacket(TradePhase.CLOSED),
          party = emptyList(),
          tradeBag = emptyMap(),
          tradeMoney = 0,
          market = MarketClient.State(),
          ackSeq = 0,
          moving = emptySet(),
      )
}
