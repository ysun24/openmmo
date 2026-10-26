package de.fiereu.openmmo.web

import de.fiereu.openmmo.client.net.BattleMon
import de.fiereu.openmmo.client.net.BattleMove
import de.fiereu.openmmo.client.net.BattlePhase
import de.fiereu.openmmo.client.net.BattleState
import de.fiereu.openmmo.client.net.MarketClient
import de.fiereu.openmmo.client.net.MarketEntry
import de.fiereu.openmmo.client.world.ChatLine
import de.fiereu.openmmo.client.world.DialogView
import de.fiereu.openmmo.client.world.Entity
import de.fiereu.openmmo.client.world.MapKey
import de.fiereu.openmmo.client.world.ShopOffer
import de.fiereu.openmmo.client.world.ShopView
import de.fiereu.openmmo.client.world.WorldState
import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.common.enums.EVs
import de.fiereu.openmmo.common.enums.IVs
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.net.game.packets.trade.TradeItemView
import de.fiereu.openmmo.net.game.packets.trade.TradeMonView
import de.fiereu.openmmo.net.game.packets.trade.TradeOfferView
import de.fiereu.openmmo.net.game.packets.trade.TradePhase
import de.fiereu.openmmo.net.game.packets.trade.TradeStage
import de.fiereu.openmmo.net.game.packets.trade.TradeStatePacket
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

private object FakeNames : Names {
  override fun species(dex: Int) =
      mapOf(1 to "BULBASAUR", 4 to "CHARMANDER", 19 to "RATTATA")[dex] ?: "#$dex"

  override fun move(id: Int) =
      mapOf(10 to "SCRATCH", 45 to "GROWL", 52 to "EMBER")[id] ?: "MOVE $id"

  override fun maxPp(id: Int) = 35

  override fun item(id: Int) = if (id == 5017) "Potion" else "#$id"

  override fun mapName(key: MapKey) = if (key == MapKey(0, 3, 0)) "PalletTown" else null

  override fun maxHp(mon: Pokemon) = 39

  override fun itemInfo(id: Int) =
      if (id == 5017) ItemInfo("items", "Restores 20 HP.", 300, "mon") else null

  override fun speaker(region: Int, gfx: Int) = if (gfx == 23) "PROF. OAK" else null
}

fun mon(dex: Int, slot: Int, nick: String = "", level: Int = 15, hp: Int = 30) =
    Pokemon(
        id = 100L + slot,
        ownerId = 1,
        container = PokemonContainer.PARTY,
        containerSlot = slot.toShort(),
        dexId = dex,
        seed = 0,
        ot = "Ash",
        nickname = nick,
        level = level.toByte(),
        hp = hp.toShort(),
        xp = 0,
        eVs = EVs(),
        iVs = IVs(),
        moves = emptyList(),
        isShiny = false,
        hasHiddenAbility = false,
        isAlpha = false,
        isSecret = false,
        isFatefulEncounter = false,
        isRaidEncounter = false,
        caughtAt = LocalDateTime.of(2026, 1, 1, 0, 0),
    )

class StateJsonTest {
  private val json = StateJson(FakeNames)
  private val selfId = 2412313315698249728L

  private fun world(): WorldState {
    val w = WorldState()
    w.selectCharacter(selfId, "Ash")
    w.enterMap(MapKey(0, 3, 0), 24, 20, clearEntities = true)
    w.upsert(Entity(selfId, "Ash", MapKey(0, 3, 0), 12, 8, Direction.DOWN, isPlayer = true))
    w.upsert(
        Entity(7, "", MapKey(0, 3, 0), 3, 4, Direction.LEFT, isPlayer = false, graphicsId = 23))
    w.upsert(Entity(8, "", MapKey(0, 3, 0), 5, 5, Direction.UP, isPlayer = false, visible = false))
    w.addChat(ChatLine("*", "Welcome to OpenMMO!"))
    return w
  }

  private fun sources(
      w: WorldState = world(),
      battle: BattleState = BattleState(),
      trade: TradeStatePacket = TradeStatePacket(TradePhase.CLOSED),
      market: MarketClient.State = MarketClient.State(),
      party: List<Pokemon> = listOf(mon(4, 0)),
  ) =
      Sources(
          world = w.snapshot(),
          battle = battle,
          trade = trade,
          party = party,
          tradeBag = mapOf(5017 to 3),
          tradeMoney = 3000,
          market = market,
          ackSeq = 41,
          moving = setOf(7L),
      )

  private fun parse(s: String): JsonElement = Json.parseToJsonElement(s)

  @Test
  fun `world section matches the protocol`() {
    val out = json.build(sources())
    assertEquals(
        parse(
            """{"map":{"region":0,"bank":3,"map":0,"name":"PalletTown","width":24,"height":20},
            "self":"$selfId","inWorld":true,
            "entities":[
              {"id":"7","kind":"npc","name":"","gfx":23,"x":3,"y":4,"facing":"left","moving":true,
               "map":{"region":0,"bank":3,"map":0}},
              {"id":"$selfId","kind":"player","name":"Ash","gfx":null,"x":12,"y":8,"facing":"down",
               "moving":false,"map":{"region":0,"bank":3,"map":0}}],
            "ackSeq":41}"""),
        out["world"])
    assertEquals(parse("""[{"sender":"*","text":"Welcome to OpenMMO!"}]"""), out["chat"])
    assertEquals(
        listOf("world", "chat", "ui", "party", "pc", "battle", "invite", "trade", "market"),
        out.keys.toList())
  }

  @Test
  fun `ui section with dialog, shop and bag`() {
    val w = world()
    w.overworld.setDialog(
        DialogView(
            seq = 1,
            actionType = 5,
            textId = 1,
            entityId = 7,
            pages = listOf(listOf("Hey! Wait!", "Don't go out!"), listOf("Want one?")),
            page = 1,
            choices = listOf("YES", "NO"),
            choiceValues = listOf(1, 0),
        ))
    w.overworld.setShop(ShopView(7, listOf(ShopOffer(5017, "POTION", 300))))
    w.overworld.setMoney(3000)
    w.overworld.setInScript(true)
    val ui = json.build(sources(w))["ui"]
    assertEquals(
        parse(
            """{"dialog":{"speaker":"PROF. OAK","lines":["Want one?"],"lastPage":true,
                 "choices":["YES","NO"],"cursor":0},
               "shop":{"offers":[{"item":5017,"name":"POTION","price":300,"desc":"Restores 20 HP."}],
                       "sell":[{"item":5017,"name":"Potion","qty":3,"price":150}],
                       "cursor":0,"status":""},
               "money":3000,
               "bag":[{"item":5017,"name":"Potion","qty":3,"pocket":"items","desc":"Restores 20 HP.",
                       "use":"mon"}],
               "locked":true,"notice":null}"""),
        ui)
  }

  @Test
  fun `choices are hidden until the last page`() {
    val w = world()
    w.overworld.setDialog(
        DialogView(1, 5, 1, 99, listOf(listOf("a"), listOf("b")), choices = listOf("YES", "NO")))
    val dialog = (json.build(sources(w))["ui"] as kotlinx.serialization.json.JsonObject)["dialog"]
    assertEquals(
        parse("""{"speaker":null,"lines":["a"],"lastPage":false,"choices":[],"cursor":0}"""),
        dialog)
  }

  @Test
  fun `party, battle and invite`() {
    val battle =
        BattleState(
            active = true,
            wild = true,
            party =
                listOf(
                    BattleMon(
                        0,
                        100,
                        4,
                        15,
                        30,
                        39,
                        listOf(BattleMove(10, 33, 35), BattleMove(52, 25, 25)))),
            opponent = listOf(BattleMon(0, 200, 19, 3, -2, 15)),
            phase = BattlePhase.ACTION,
            turn = 3,
            log = listOf("CHARMANDER used SCRATCH!"),
            pendingLearn = 100L to listOf(52),
            pendingInviteFrom = "Gary",
        )
    val out =
        json.build(sources(battle = battle, party = listOf(mon(4, 0), mon(1, 1, nick = "Bulby"))))
    assertEquals(
        parse(
            """[{"slot":0,"dex":4,"name":"CHARMANDER","nick":null,"level":15,"hp":30,"maxHp":39,
                 "id":"100","moves":[],"ot":"Ash","xp":0,"shiny":false},
                {"slot":1,"dex":1,"name":"BULBASAUR","nick":"Bulby","level":15,"hp":30,"maxHp":39,
                 "id":"101","moves":[],"ot":"Ash","xp":0,"shiny":false}]"""),
        out["party"])
    assertEquals(
        parse(
            """{"wild":true,"opponentName":"","phase":"ACTION","turn":3,
               "me":{"dex":4,"name":"CHARMANDER","level":15,"hp":30,"maxHp":39,
                     "moves":[{"name":"SCRATCH","pp":33,"maxPp":35},{"name":"EMBER","pp":25,"maxPp":25}]},
               "foe":{"dex":19,"name":"RATTATA","level":3,"hp":0,"maxHp":15},
               "party":[{"dex":4,"name":"CHARMANDER","level":15,"hp":30,"maxHp":39}],
               "log":["CHARMANDER used SCRATCH!"],"result":null,
               "learn":{"move":"EMBER","current":["SCRATCH","EMBER"]}}"""),
        out["battle"])
    assertEquals(parse("""{"from":"Gary"}"""), out["invite"])
  }

  @Test
  fun `no battle, invite or trade are null`() {
    val out = json.build(sources())
    assertEquals(JsonNull, out["battle"])
    assertEquals(JsonNull, out["invite"])
    assertEquals(JsonNull, out["trade"])
  }

  @Test
  fun `trade section`() {
    val t =
        TradeStatePacket(
            TradePhase.OPEN,
            "Gary",
            mine =
                TradeOfferView(mons = listOf(TradeMonView(1, 1, 12, "", false, 30)), money = 500),
            theirs = TradeOfferView(items = listOf(TradeItemView(5017, 2))),
            myStage = TradeStage.READY,
        )
    assertEquals(
        parse(
            """{"phase":"OPEN","partner":"Gary","message":"",
               "mine":{"stage":"READY","mons":[{"slot":1,"dex":1,"name":"BULBASAUR","level":12}],
                       "items":[],"money":500},
               "theirs":{"stage":"NONE","mons":[],"items":[{"item":5017,"name":"Potion","qty":2}],
                         "money":0}}"""),
        json.trade(t))
    // A closed trade with a reason is shown once more with that reason.
    val closed =
        json.trade(TradeStatePacket(TradePhase.CLOSED, "Gary", message = "Gary cancelled."))
    assertTrue(closed.toString().contains("\"phase\":\"CLOSED\""))
  }

  @Test
  fun `market section`() {
    val own = MarketEntry(1, false, 5017, null, null, 2, 150, 1793000000, null)
    val other = MarketEntry(2412313315698249729, true, 19, 12, null, 1, 1500, 1793000000, 99)
    val state =
        MarketClient.State(
            money = 80200,
            party = listOf(mon(4, 0)),
            browse = listOf(other, own),
            browseTotal = 2,
            mine = listOf(own),
            log = listOf("BUY ok: Bought 2x Potion for 300."))
    val m = json.build(sources(market = state))["market"]
    assertEquals(
        parse(
            """{"money":80200,
               "browse":[{"id":"2412313315698249729","kind":"mon","dex":19,"item":null,"name":"RATTATA",
                          "level":12,"qty":1,"price":1500,"expiresAt":1793000000,"mine":false},
                         {"id":"1","kind":"item","dex":null,"item":5017,"name":"Potion","level":null,
                          "qty":2,"price":150,"expiresAt":1793000000,"mine":true}],
               "browseTotal":2,"page":0,
               "mine":[{"id":"1","kind":"item","dex":null,"item":5017,"name":"Potion","level":null,
                        "qty":2,"price":150,"expiresAt":1793000000,"mine":true}],
               "inventory":{"party":[{"slot":0,"dex":4,"name":"CHARMANDER","nick":null,"level":15,
                                      "hp":30,"maxHp":39,"id":"100","moves":[],"ot":"Ash",
                                      "xp":0,"shiny":false}],"bag":[]},
               "log":["BUY ok: Bought 2x Potion for 300."]}"""),
        m)
  }

  @Test
  fun `differ sends everything first, then only changes`() {
    val d = SectionDiffer()
    val first = json.build(sources())
    assertEquals(first.keys, d.diff(first).keys)
    assertTrue(d.diff(json.build(sources())).isEmpty())
    val w = world()
    w.move(selfId, 13, 8, Direction.RIGHT)
    assertEquals(setOf("world"), d.diff(json.build(sources(w))).keys)
    val inBattle = json.build(sources(w, battle = BattleState(active = true)))
    assertEquals(setOf("battle"), d.diff(inBattle).keys)
    // Ending the battle sends it as null.
    val after = d.diff(json.build(sources(w)))
    assertEquals(mapOf<String, JsonElement>("battle" to JsonNull), after)
  }

  @Test
  fun `movement tracker marks a moved entity for one step duration`() {
    val t = MovementTracker(windowMs = 300)
    val e = Entity(1, "a", MapKey(0, 0, 0), 1, 1, Direction.DOWN, true)
    assertEquals(emptySet(), t.update(listOf(e), 1000))
    assertEquals(setOf(1L), t.update(listOf(e.copy(x = 2)), 1100))
    assertTrue(t.anyMoving(1200))
    assertEquals(emptySet(), t.update(listOf(e.copy(x = 2)), 1500))
    assertTrue(!t.anyMoving(1500))
  }

  @Test
  fun `state message envelope`() {
    val msg = WsSession.stateMessage(3, mapOf("battle" to JsonNull))
    assertEquals("""{"t":"state","seq":3,"battle":null}""", msg.toString())
  }

  @Test
  fun `party summary, pc and a notice`() {
    val w = world()
    w.overworld.setNotice("CHARMANDER's HP was restored by 9 point(s).")
    val boxed = mon(19, 3).copy(container = PokemonContainer.PC)
    val out =
        StateJson(
                object : Names by FakeNames {
                  override fun summary(mon: Pokemon) =
                      MonSummary(mapOf("hp" to 39, "atk" to 20), "HARDY", "BLAZE", listOf("FIRE"))

                  override fun hasPc(key: MapKey) = true
                })
            .build(sources(w, party = listOf(mon(4, 0))).copy(pc = listOf(boxed)))
    assertEquals(
        parse(
            """[{"slot":0,"dex":4,"name":"CHARMANDER","nick":null,"level":15,"hp":30,"maxHp":39,
                 "id":"100","stats":{"hp":39,"atk":20},"nature":"HARDY","ability":"BLAZE",
                 "types":["FIRE"],"moves":[],"ot":"Ash","xp":0,"shiny":false}]"""),
        out["party"])
    assertEquals(
        parse(
            """{"here":true,"mons":[{"slot":3,"id":"103","dex":19,"name":"RATTATA","level":15,
                 "hp":30,"maxHp":39}]}"""),
        out["pc"])
    assertEquals(
        parse("""{"seq":1,"text":"CHARMANDER's HP was restored by 9 point(s)."}"""),
        (out["ui"] as kotlinx.serialization.json.JsonObject)["notice"])
  }
}
