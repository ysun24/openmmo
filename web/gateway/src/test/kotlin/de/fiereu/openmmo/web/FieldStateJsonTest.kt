package de.fiereu.openmmo.web

import de.fiereu.openmmo.client.net.BattleState
import de.fiereu.openmmo.client.net.MarketClient
import de.fiereu.openmmo.client.world.Entity
import de.fiereu.openmmo.client.world.MapKey
import de.fiereu.openmmo.client.world.WorldState
import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.net.game.packets.field.FieldAction
import de.fiereu.openmmo.net.game.packets.field.FieldInfoPacket
import de.fiereu.openmmo.net.game.packets.field.FlyDestination
import de.fiereu.openmmo.net.game.packets.field.TravelMode
import de.fiereu.openmmo.net.game.packets.trade.TradePhase
import de.fiereu.openmmo.net.game.packets.trade.TradeStatePacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private object FieldNames : Names {
  override fun species(dex: Int) = "#$dex"

  override fun move(id: Int) = "MOVE $id"

  override fun maxPp(id: Int) = 35

  override fun item(id: Int) = "#$id"

  override fun mapName(key: MapKey) =
      mapOf(MapKey(0, 3, 0) to "PalletTown", MapKey(0, 3, 1) to "ViridianCity")[key]

  override fun maxHp(mon: Pokemon) = 39

  override fun itemInfo(id: Int): ItemInfo? = null

  override fun speaker(region: Int, gfx: Int): String? = null
}

/** r3-field additions to the state sections: entity travel state, world.field, ui.open. */
class FieldStateJsonTest {
  private val json = StateJson(FieldNames)
  private val selfId = 77L

  private fun sources(w: WorldState) =
      Sources(
          world = w.snapshot(),
          battle = BattleState(),
          trade = TradeStatePacket(TradePhase.CLOSED),
          party = emptyList(),
          tradeBag = emptyMap(),
          tradeMoney = 0,
          market = MarketClient.State(),
          ackSeq = 1,
          moving = emptySet(),
      )

  private fun world(): WorldState {
    val w = WorldState()
    w.selectCharacter(selfId, "Ash")
    w.enterMap(MapKey(0, 3, 0), 24, 20, clearEntities = true)
    w.upsert(Entity(selfId, "Ash", MapKey(0, 3, 0), 9, 17, Direction.DOWN, isPlayer = true))
    return w
  }

  @Test
  fun `entities carry their travel mode, run state and jump counter`() {
    val w = world()
    w.update(selfId) {
      it.copy(travel = TravelMode.SURF, running = true, jumpSeq = 2, exclaimSeq = 1)
    }
    val e = (json.world(sources(w)) as JsonObject)["entities"].toString()
    val one = Json.parseToJsonElement(e).let { (it as kotlinx.serialization.json.JsonArray)[0] }
    one as JsonObject
    assertEquals("\"surf\"", one["mode"].toString())
    assertEquals("true", one["run"].toString())
    assertEquals("2", one["jump"].toString())
    assertEquals("1", one["exclaim"].toString())
  }

  @Test
  fun `walking entities keep the old compact shape`() {
    val one = (json.entity(world().snapshot().self!!, false) as JsonObject)
    assertNull(one["mode"])
    assertNull(one["jump"])
  }

  @Test
  fun `world field and ui open come from the last FieldInfoPacket`() {
    val w = world()
    w.setField(
        FieldInfoPacket(
            FieldInfoPacket.FLAG_DARK or
                FieldInfoPacket.FLAG_CAN_RUN or
                FieldInfoPacket.FLAG_HAS_BIKE,
            listOf(FieldAction.SURF, FieldAction.ROCK_SMASH),
            listOf(FlyDestination(3, 1)),
            uiSeq = 4,
            uiKind = 1))
    val f = (json.world(sources(w)) as JsonObject)["field"] as JsonObject
    assertEquals("true", f["dark"].toString())
    assertEquals("false", f["flash"].toString())
    assertEquals("true", f["canRun"].toString())
    assertEquals("true", f["hasBike"].toString())
    assertEquals("[\"surf\",\"rocksmash\"]", f["moves"].toString())
    assertEquals("[{\"bank\":3,\"map\":1,\"name\":\"ViridianCity\"}]", f["fly"].toString())
    val open = (json.ui(sources(w)) as JsonObject)["open"] as JsonObject
    assertEquals("{\"seq\":4,\"kind\":\"pc\"}", open.toString())
  }

  @Test
  fun `field messages route to the field action`() {
    val calls = mutableListOf<List<Any?>>()
    val g =
        java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(GameActions::class.java)) { _, m, args ->
              calls += listOf(m.name) + (args?.toList() ?: emptyList())
              null
            } as GameActions
    fun send(s: String) = Dispatcher.dispatch("field", Json.parseToJsonElement(s) as JsonObject, g)
    send("""{"t":"field","action":"surf"}""")
    send("""{"t":"field","action":"fly","bank":3,"map":1}""")
    assertEquals("unknown field action: teleportx", send("""{"t":"field","action":"teleportx"}"""))
    assertEquals<List<List<Any?>>>(
        listOf(listOf("field", "surf", 0, 0), listOf("field", "fly", 3, 1)), calls)
  }
}
