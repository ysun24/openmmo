package de.fiereu.openmmo.server.game.services.trade

import de.fiereu.network.PacketEvent
import de.fiereu.openmmo.common.enums.CharacterGender
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.moves.MoveRegistry
import de.fiereu.openmmo.net.game.packets.ChatMessagePacket
import de.fiereu.openmmo.net.game.packets.GbaEntityMovePacket
import de.fiereu.openmmo.net.game.packets.PokemonContainerPacket
import de.fiereu.openmmo.net.game.packets.TradeActionPacket
import de.fiereu.openmmo.net.game.packets.TradeSelectMonPacket
import de.fiereu.openmmo.net.game.packets.social.TradeOfferPcMonPacket
import de.fiereu.openmmo.net.game.packets.trade.TradeAction
import de.fiereu.openmmo.net.game.packets.trade.TradeOfferItemPacket
import de.fiereu.openmmo.net.game.packets.trade.TradeOfferKind
import de.fiereu.openmmo.net.game.packets.trade.TradePhase
import de.fiereu.openmmo.net.game.packets.trade.TradeRequestPacket
import de.fiereu.openmmo.net.game.packets.trade.TradeStage
import de.fiereu.openmmo.net.game.packets.trade.TradeStatePacket
import de.fiereu.openmmo.pokemon.LearnsetRegistry
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import de.fiereu.openmmo.server.game.battle.BattlePacketEmitter
import de.fiereu.openmmo.server.game.battle.BattleRegistry
import de.fiereu.openmmo.server.game.battle.BattleRewards
import de.fiereu.openmmo.server.game.battle.MoveLearner
import de.fiereu.openmmo.server.game.battle.TurnEngine
import de.fiereu.openmmo.server.game.battle.WildMonFactory
import de.fiereu.openmmo.server.game.services.BattleService
import de.fiereu.openmmo.server.game.services.PvpBattleService
import de.fiereu.openmmo.server.game.services.WorldStateService
import de.fiereu.openmmo.server.game.services.social.BlockList
import de.fiereu.openmmo.server.game.services.social.Rate
import de.fiereu.openmmo.server.game.services.social.RateKind
import de.fiereu.openmmo.server.game.services.social.SocialConfig
import de.fiereu.openmmo.server.game.services.social.TradeProximity
import de.fiereu.openmmo.server.game.session.SessionRegistry
import de.fiereu.openmmo.server.game.storage.CharacterStore
import de.fiereu.openmmo.server.game.storage.EntityIdService
import de.fiereu.openmmo.server.game.storage.InMemorySocialRepository
import de.fiereu.openmmo.server.game.testsupport.FakeCharacterRepository
import de.fiereu.openmmo.server.game.testsupport.FakeSession
import de.fiereu.openmmo.server.game.world.interest.InterestManager
import de.fiereu.openmmo.trainer.TrainerRegistry
import de.fiereu.openmmo.typechart.TypeChart
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

private const val POTION = 5017

private class TradeFixture(
    scope: CoroutineScope,
    val config: SocialConfig =
        SocialConfig(rates = RateKind.entries.associateWith { Rate(1000, 1) }),
    val evolution: TradeEvolutionHook = TradeEvolutionHook.NONE,
) {
  val repo = FakeCharacterRepository()
  val store = CharacterStore(repo, EntityIdService(), scope)
  val registry = SessionRegistry()
  val interest = InterestManager()
  val battles =
      BattleService(
          characterStore = store,
          battles = BattleRegistry(),
          engine = TurnEngine(MoveRegistry(), TypeChart()),
          wildMons =
              WildMonFactory(
                  SpeciesRegistry(), MoveRegistry(), LearnsetRegistry(), EntityIdService()),
          emitter = BattlePacketEmitter(interest),
          rewards = BattleRewards(),
          moveLearner = MoveLearner(LearnsetRegistry(), MoveRegistry()),
          interestManager = interest,
          speciesRegistry = SpeciesRegistry(),
          moveRegistry = MoveRegistry(),
          trainers = TrainerRegistry(),
          items = ItemRegistry(),
          pvp =
              PvpBattleService(
                  store,
                  SessionRegistry(),
                  BattleRegistry(),
                  TurnEngine(MoveRegistry(), TypeChart()),
                  BattlePacketEmitter(interest),
                  interest,
                  SpeciesRegistry()),
      )
  val social = InMemorySocialRepository()
  val blocks = BlockList(social)
  val service =
      TradeService(
          store,
          registry,
          battles,
          WorldStateService(),
          ItemRegistry(),
          config = config,
          history = social,
          blocks = blocks,
          evolution = evolution,
          scope = scope)

  suspend fun player(name: String, partyDex: List<Int>): Pair<FakeSession, Long> {
    val created = store.createCharacter(1, name, CharacterGender.MALE, Region.HOENN)
    val id = created.info.id
    partyDex.forEachIndexed { i, dex -> store.addPokemon(id, tradeMon(id, i, dex)) }
    store.addItem(id, POTION, 5)
    val session = FakeSession(id)
    registry.bindCharacter(session, id)
    social.register(id, name)
    return session to id
  }

  suspend fun request(s: FakeSession, name: String) =
      service.onRequest(PacketEvent(TradeRequestPacket(name), s))

  suspend fun act(s: FakeSession, action: Byte) =
      service.onAction(PacketEvent(TradeActionPacket(action), s))

  fun mon(s: FakeSession, slot: Int) =
      service.onSelectMon(PacketEvent(TradeSelectMonPacket(slot), s))

  fun item(s: FakeSession, id: Int, qty: Int) =
      service.onOfferItem(PacketEvent(TradeOfferItemPacket(TradeOfferKind.ITEM, id, qty), s))

  fun money(s: FakeSession, amount: Int) =
      service.onOfferItem(PacketEvent(TradeOfferItemPacket(TradeOfferKind.MONEY, 0, amount), s))

  fun pcMon(s: FakeSession, slot: Int) =
      service.onSelectPcMon(PacketEvent(TradeOfferPcMonPacket(slot), s))

  fun dex(id: Long): List<Int> = store.getCharacter(id)!!.pokemon.map { it.dexId }
}

private fun FakeSession.lastTrade(): TradeStatePacket =
    sent.filterIsInstance<TradeStatePacket>().lastOrNull().shouldNotBeNull()

@OptIn(ExperimentalCoroutinesApi::class)
class TradeServiceTest :
    FunSpec({
      suspend fun TradeFixture.openTrade(): List<Pair<FakeSession, Long>> {
        val ash = player("Ash", listOf(1, 4))
        val gary = player("Gary", listOf(25, 133))
        request(ash.first, "gary")
        ash.first.lastTrade().phase shouldBe TradePhase.REQUEST_SENT
        gary.first.lastTrade().phase shouldBe TradePhase.REQUEST_RECEIVED
        act(gary.first, TradeAction.ACCEPT)
        ash.first.lastTrade().phase shouldBe TradePhase.OPEN
        return listOf(ash, gary)
      }

      test("a full trade swaps monsters, items and money and resyncs both parties") {
        runTest {
          val fx = TradeFixture(backgroundScope)
          val (ash, gary) = fx.openTrade()
          fx.mon(ash.first, 1)
          fx.item(ash.first, POTION, 3)
          fx.mon(gary.first, 0)
          fx.money(gary.first, 500)
          gary.first.lastTrade().theirs.mons.single().dexId shouldBe 4
          gary.first.lastTrade().theirs.items.single().quantity shouldBe 3

          fx.act(ash.first, TradeAction.READY)
          fx.act(gary.first, TradeAction.READY)
          fx.act(ash.first, TradeAction.CONFIRM)
          ash.first.lastTrade().myStage shouldBe TradeStage.CONFIRMED
          fx.dex(ash.second) shouldContainExactly listOf(1, 4)
          fx.act(gary.first, TradeAction.CONFIRM)

          fx.dex(ash.second) shouldContainExactly listOf(1, 25)
          fx.dex(gary.second) shouldContainExactly listOf(133, 4)
          val a = fx.store.getCharacter(ash.second)!!
          val g = fx.store.getCharacter(gary.second)!!
          a.items[POTION] shouldBe 2
          g.items[POTION] shouldBe 8
          a.info.money shouldBe 30000 + 500
          g.info.money shouldBe 30000 - 500
          // Written, not just in memory.
          fx.repo.saved[ash.second]!!.pokemon.map { it.dexId } shouldContainExactly listOf(1, 25)
          fx.repo.saved[gary.second]!!.info.money shouldBe 29500
          ash.first.lastTrade().phase shouldBe TradePhase.COMPLETED
          ash.first.sent.filterIsInstance<PokemonContainerPacket>().isNotEmpty() shouldBe true
          fx.service.isTrading(ash.second) shouldBe false
        }
      }

      test("any change to an offer drops both confirmations") {
        runTest {
          val fx = TradeFixture(backgroundScope)
          val (ash, gary) = fx.openTrade()
          fx.mon(ash.first, 0)
          fx.act(ash.first, TradeAction.READY)
          fx.act(gary.first, TradeAction.READY)
          fx.act(ash.first, TradeAction.CONFIRM)
          fx.item(gary.first, POTION, 1)
          ash.first.lastTrade().myStage shouldBe TradeStage.NONE
          ash.first.lastTrade().theirStage shouldBe TradeStage.NONE
          // Confirming now is refused because nobody is ready any more.
          fx.act(gary.first, TradeAction.CONFIRM)
          fx.dex(ash.second) shouldContainExactly listOf(1, 4)
          gary.first.lastTrade().myStage shouldBe TradeStage.NONE
        }
      }

      test("confirming before the partner is ready does nothing") {
        runTest {
          val fx = TradeFixture(backgroundScope)
          val (ash, _) = fx.openTrade()
          fx.mon(ash.first, 0)
          fx.act(ash.first, TradeAction.READY)
          fx.act(ash.first, TradeAction.CONFIRM)
          ash.first.lastTrade().myStage shouldBe TradeStage.READY
        }
      }

      test("offers are validated: no foreign slots, no more than held, no key items") {
        runTest {
          val fx = TradeFixture(backgroundScope)
          val (ash, _) = fx.openTrade()
          fx.mon(ash.first, 5)
          fx.item(ash.first, POTION, 6)
          fx.item(ash.first, 5450, 1) // Bicycle, not held and a key item.
          fx.money(ash.first, 30001)
          fx.item(ash.first, POTION, -1)
          ash.first.lastTrade().mine.mons shouldBe emptyList()
          ash.first.lastTrade().mine.items shouldBe emptyList()
          ash.first.lastTrade().mine.money shouldBe 0
        }
      }

      test("offering a monster twice takes it back off the table") {
        runTest {
          val fx = TradeFixture(backgroundScope)
          val (ash, _) = fx.openTrade()
          fx.mon(ash.first, 0)
          ash.first.lastTrade().mine.mons.size shouldBe 1
          fx.mon(ash.first, 0)
          ash.first.lastTrade().mine.mons.size shouldBe 0
        }
      }

      test("the last party monster cannot go, and the trade stays open") {
        runTest {
          val fx = TradeFixture(backgroundScope)
          val ash = fx.player("Ash", listOf(1))
          val gary = fx.player("Gary", listOf(25))
          fx.request(ash.first, "Gary")
          fx.act(gary.first, TradeAction.ACCEPT)
          fx.mon(ash.first, 0)
          fx.money(gary.first, 1)
          for (s in listOf(ash.first, gary.first)) fx.act(s, TradeAction.READY)
          for (s in listOf(ash.first, gary.first)) fx.act(s, TradeAction.CONFIRM)
          fx.dex(ash.second) shouldContainExactly listOf(1)
          ash.first.lastTrade().phase shouldBe TradePhase.OPEN
          ash.first.lastTrade().myStage shouldBe TradeStage.NONE
          ash.first.lastTrade().message.contains("last party monster") shouldBe true
        }
      }

      test("cancel and disconnect close the window on both sides and change nothing") {
        runTest {
          val fx = TradeFixture(backgroundScope)
          val (ash, gary) = fx.openTrade()
          fx.mon(ash.first, 0)
          fx.act(gary.first, TradeAction.CANCEL)
          ash.first.lastTrade().phase shouldBe TradePhase.CLOSED
          gary.first.lastTrade().phase shouldBe TradePhase.CLOSED
          fx.service.isTrading(ash.second) shouldBe false

          fx.request(ash.first, "Gary")
          fx.act(gary.first, TradeAction.ACCEPT)
          fx.service.onDisconnect(gary.second)
          ash.first.lastTrade().phase shouldBe TradePhase.CLOSED
          ash.first.lastTrade().message shouldBe "Gary left the game."
          fx.dex(ash.second) shouldContainExactly listOf(1, 4)
        }
      }

      test("a declined request closes it") {
        runTest {
          val fx = TradeFixture(backgroundScope)
          val ash = fx.player("Ash", listOf(1))
          val gary = fx.player("Gary", listOf(25))
          fx.request(ash.first, "Gary")
          fx.act(gary.first, TradeAction.DECLINE)
          ash.first.lastTrade().phase shouldBe TradePhase.CLOSED
          // The requester cannot accept their own request.
          fx.request(ash.first, "Gary")
          fx.act(ash.first, TradeAction.ACCEPT)
          ash.first.lastTrade().phase shouldBe TradePhase.REQUEST_SENT
        }
      }

      test("busy players cannot be asked: offline, already trading, in battle, in a script") {
        runTest {
          val fx = TradeFixture(backgroundScope)
          val ash = fx.player("Ash", listOf(1))
          val gary = fx.player("Gary", listOf(25))
          val misty = fx.player("Misty", listOf(120))
          fx.request(ash.first, "Nobody")
          ash.first.lastTrade().message shouldBe "Nobody is not online."
          fx.request(ash.first, "Ash")
          ash.first.lastTrade().message shouldBe "You cannot trade with yourself."

          fx.request(ash.first, "Gary")
          fx.request(misty.first, "Gary")
          misty.first.lastTrade().message shouldBe "Gary is already trading."
          fx.act(ash.first, TradeAction.CANCEL)

          gary.first.state().inDialog = true
          fx.request(ash.first, "Gary")
          ash.first.lastTrade().message shouldBe "Gary is busy."
          gary.first.state().inDialog = false

          fx.battles.startWildBattle(misty.first, 19, 2)
          fx.request(ash.first, "Misty")
          ash.first.lastTrade().message shouldBe "Misty is in a battle."
        }
      }

      test("a battle started after the offers blocks the swap at confirmation") {
        runTest {
          val fx = TradeFixture(backgroundScope)
          val (ash, gary) = fx.openTrade()
          fx.mon(ash.first, 0)
          fx.act(ash.first, TradeAction.READY)
          fx.act(gary.first, TradeAction.READY)
          fx.battles.startWildBattle(gary.first, 19, 2)
          fx.act(ash.first, TradeAction.CONFIRM)
          fx.act(gary.first, TradeAction.CONFIRM)
          fx.dex(ash.second) shouldContainExactly listOf(1, 4)
          ash.first.lastTrade().message shouldBe "Gary is in a battle."
        }
      }

      test("a failed write leaves both characters as they were") {
        runTest {
          val fx = TradeFixture(backgroundScope)
          val (ash, gary) = fx.openTrade()
          fx.mon(ash.first, 0)
          fx.mon(gary.first, 0)
          fx.act(ash.first, TradeAction.READY)
          fx.act(gary.first, TradeAction.READY)
          fx.act(ash.first, TradeAction.CONFIRM)
          fx.repo.failNextSave = true
          fx.act(gary.first, TradeAction.CONFIRM)
          fx.dex(ash.second) shouldContainExactly listOf(1, 4)
          fx.dex(gary.second) shouldContainExactly listOf(25, 133)
          ash.first.lastTrade().message shouldBe "The trade could not be saved."
          fx.service.isTrading(ash.second) shouldBe true
        }
      }

      test("a PC monster can be offered and lands with the partner") {
        runTest {
          val fx = TradeFixture(backgroundScope)
          val (ash, gary) = fx.openTrade()
          val a = fx.store.getCharacter(ash.second)!!
          // Move Charmander (party slot 1) into the PC, slot 0.
          val charmander = a.pokemon.first { it.dexId == 4 }
          fx.store.rearrangeMonsters(
              ash.second,
              a.pokemon.filter { it.id != charmander.id },
              listOf(charmander.copy(container = PokemonContainer.PC, containerSlot = 0))) shouldBe
              true
          fx.pcMon(ash.first, 0)
          ash.first.lastTrade().mine.pcMons.single().dexId shouldBe 4
          ash.first.lastTrade().mine.mons shouldBe emptyList()
          gary.first.lastTrade().theirs.pcMons.single().partySlot shouldBe 0
          fx.money(gary.first, 100)
          fx.act(ash.first, TradeAction.READY)
          fx.act(gary.first, TradeAction.READY)
          fx.act(ash.first, TradeAction.CONFIRM)
          fx.act(gary.first, TradeAction.CONFIRM)
          fx.store.getCharacter(ash.second)!!.pcStorage shouldBe emptyList()
          fx.dex(gary.second) shouldContainExactly listOf(25, 133, 4)
          fx.store.getCharacter(gary.second)!!.pokemon.last().ownerId shouldBe gary.second
        }
      }

      test("pc slot that does not exist is refused") {
        runTest {
          val fx = TradeFixture(backgroundScope)
          val (ash, _) = fx.openTrade()
          fx.pcMon(ash.first, 3)
          ash.first.lastTrade().mine.pcMons shouldBe emptyList()
          ash.first.sent.filterIsInstance<ChatMessagePacket>().last().message shouldBe
              "There is no monster in PC slot 3."
        }
      }

      test("an unanswered request lapses after the configured time") {
        runTest {
          val fx = TradeFixture(backgroundScope, SocialConfig(tradeRequestSeconds = 60))
          val ash = fx.player("Ash", listOf(1, 4))
          val gary = fx.player("Gary", listOf(25, 133))
          fx.request(ash.first, "Gary")
          gary.first.lastTrade().expiresInSeconds shouldBe 60
          advanceTimeBy(59_000)
          fx.service.isTrading(ash.second) shouldBe true
          advanceTimeBy(2_000)
          fx.service.isTrading(ash.second) shouldBe false
          gary.first.lastTrade().phase shouldBe TradePhase.CLOSED
          gary.first.lastTrade().message shouldBe "The trade request expired."
        }
      }

      test("an accepted trade does not lapse") {
        runTest {
          val fx = TradeFixture(backgroundScope, SocialConfig(tradeRequestSeconds = 5))
          val (ash, _) = fx.openTrade()
          advanceTimeBy(10_000)
          fx.service.isTrading(ash.second) shouldBe true
        }
      }

      test("proximity: same map by default, a tile radius, or anywhere") {
        runTest {
          val fx = TradeFixture(backgroundScope)
          val ash = fx.player("Ash", listOf(1, 4))
          val gary = fx.player("Gary", listOf(25, 133))
          val a = fx.store.getCharacter(ash.second)!!
          fx.store.updatePosition(gary.second, 1, 1, mapId = (a.info.positionMapId + 1).toByte())
          fx.request(ash.first, "Gary")
          ash.first.lastTrade().message shouldBe "Gary has to be on the same map to trade."

          val near =
              TradeFixture(backgroundScope, SocialConfig(tradeProximity = TradeProximity.Within(2)))
          val b = near.player("Ash", listOf(1, 4))
          val c = near.player("Gary", listOf(25, 133))
          near.store.updatePosition(b.second, 1, 1)
          near.store.updatePosition(c.second, 4, 1)
          near.request(b.first, "Gary")
          b.first.lastTrade().message shouldBe "Stand within 2 steps of Gary to trade."
          near.store.updatePosition(c.second, 2, 2)
          near.request(b.first, "Gary")
          b.first.lastTrade().phase shouldBe TradePhase.REQUEST_SENT

          val any =
              TradeFixture(backgroundScope, SocialConfig(tradeProximity = TradeProximity.Anywhere))
          val d = any.player("Ash", listOf(1, 4))
          val e = any.player("Gary", listOf(25, 133))
          any.store.updatePosition(e.second, 1, 1, mapId = 99)
          any.request(d.first, "Gary")
          d.first.lastTrade().phase shouldBe TradePhase.REQUEST_SENT
        }
      }

      test("a player who blocked you does not get your requests") {
        runTest {
          val fx = TradeFixture(backgroundScope)
          val ash = fx.player("Ash", listOf(1, 4))
          val gary = fx.player("Gary", listOf(25, 133))
          fx.social.block(gary.second, ash.second, java.time.LocalDateTime.now())
          fx.request(ash.first, "Gary")
          ash.first.lastTrade().message shouldBe "Gary is not accepting trade requests."
          gary.first.sent.filterIsInstance<TradeStatePacket>() shouldBe emptyList()
        }
      }

      test("trade requests are rate limited per account") {
        runTest {
          val fx =
              TradeFixture(
                  backgroundScope,
                  SocialConfig(rates = mapOf(RateKind.TRADE_REQUEST to Rate(1, 60))))
          val ash = fx.player("Ash", listOf(1, 4))
          fx.player("Gary", listOf(25, 133))
          fx.request(ash.first, "Gary")
          fx.act(ash.first, TradeAction.CANCEL)
          fx.request(ash.first, "Gary")
          ash.first.lastTrade().message shouldBe "You are sending trade requests too fast."
        }
      }

      test("an open window locks walking, shops and npcs until it closes") {
        runTest {
          val fx = TradeFixture(backgroundScope)
          val ash = fx.player("Ash", listOf(1, 4))
          val gary = fx.player("Gary", listOf(25, 133))
          fx.request(ash.first, "Gary")
          // A pending request does not lock anyone yet.
          fx.service.locksField(ash.second) shouldBe false
          fx.act(gary.first, TradeAction.ACCEPT)
          fx.service.locksField(ash.second) shouldBe true
          fx.service.refuseStep(ash.first) shouldBe true
          ash.first.sent.last().shouldBeInstanceOf<GbaEntityMovePacket>()
          fx.service.refuseFieldAction(gary.first) shouldBe true
          (gary.first.sent.last() as ChatMessagePacket).message shouldBe "Finish your trade first."
          fx.act(gary.first, TradeAction.CANCEL)
          fx.service.locksField(ash.second) shouldBe false
          fx.service.refuseStep(ash.first) shouldBe false
        }
      }

      test("a completed trade is logged and every received monster goes through onTraded") {
        runTest {
          val traded = mutableListOf<Pair<Long, Int>>()
          val fx =
              TradeFixture(
                  backgroundScope, evolution = { owner, mon -> traded += owner to mon.dexId })
          val (ash, gary) = fx.openTrade()
          fx.mon(ash.first, 1)
          fx.mon(gary.first, 0)
          fx.item(gary.first, POTION, 2)
          fx.act(ash.first, TradeAction.READY)
          fx.act(gary.first, TradeAction.READY)
          fx.act(ash.first, TradeAction.CONFIRM)
          fx.act(gary.first, TradeAction.CONFIRM)
          traded shouldContainExactlyInAnyOrder listOf(ash.second to 25, gary.second to 4)
          val log = fx.social.tradeHistory(ash.second, 10).single()
          log.partner shouldBe "Gary"
          log.gave shouldBe "CHARMANDER Lv5"
          log.got shouldBe "PIKACHU Lv5, 2x Potion"
          fx.social.tradeHistory(gary.second, 10).single().gave shouldBe "PIKACHU Lv5, 2x Potion"
        }
      }
    })
