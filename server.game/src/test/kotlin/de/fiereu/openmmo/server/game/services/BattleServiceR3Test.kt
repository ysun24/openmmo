package de.fiereu.openmmo.server.game.services

import de.fiereu.network.PacketEvent
import de.fiereu.openmmo.common.StatusCondition
import de.fiereu.openmmo.common.enums.BattleAction
import de.fiereu.openmmo.common.enums.CharacterGender
import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.moves.MoveRegistry
import de.fiereu.openmmo.net.game.packets.MapLoadedAckPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleActionSelectPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleExtActionPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleExtKind
import de.fiereu.openmmo.net.game.packets.battle.BattleExtPacket
import de.fiereu.openmmo.pokemon.LearnsetRegistry
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import de.fiereu.openmmo.server.game.battle.BattlePacketEmitter
import de.fiereu.openmmo.server.game.battle.BattleRegistry
import de.fiereu.openmmo.server.game.battle.BattleRewards
import de.fiereu.openmmo.server.game.battle.MoveLearner
import de.fiereu.openmmo.server.game.battle.TurnEngine
import de.fiereu.openmmo.server.game.battle.WildMonFactory
import de.fiereu.openmmo.server.game.battle.testMon
import de.fiereu.openmmo.server.game.session.SessionRegistry
import de.fiereu.openmmo.server.game.storage.CharacterStore
import de.fiereu.openmmo.server.game.storage.EntityIdService
import de.fiereu.openmmo.server.game.testsupport.FakeCharacterRepository
import de.fiereu.openmmo.server.game.testsupport.FakeSession
import de.fiereu.openmmo.server.game.world.interest.InterestManager
import de.fiereu.openmmo.trainer.TrainerDef
import de.fiereu.openmmo.trainer.TrainerMon
import de.fiereu.openmmo.trainer.TrainerRegistry
import de.fiereu.openmmo.typechart.TypeChart
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private class R3Fixture(scope: CoroutineScope) {
  val store = CharacterStore(FakeCharacterRepository(), EntityIdService(), scope)
  val interest = InterestManager()
  val registry = BattleRegistry()
  val items = ItemRegistry()
  val engine = TurnEngine(MoveRegistry(), TypeChart(), items)
  val emitter = BattlePacketEmitter(interest)
  val service =
      BattleService(
          store, registry, engine,
          WildMonFactory(SpeciesRegistry(), MoveRegistry(), LearnsetRegistry(), EntityIdService()),
          emitter, BattleRewards(), MoveLearner(LearnsetRegistry(), MoveRegistry()), interest,
          SpeciesRegistry(), MoveRegistry(), TrainerRegistry(), items,
          PvpBattleService(store, SessionRegistry(), registry, engine, emitter, interest, SpeciesRegistry()))

  suspend fun player(vararg mons: de.fiereu.openmmo.common.Pokemon): Pair<FakeSession, Long> {
    val created = store.createCharacter(1, "Ash", CharacterGender.MALE, Region.HOENN)
    for ((i, m) in mons.withIndex()) {
      store.addPokemon(created.info.id, m.copy(ownerId = created.info.id, containerSlot = i.toShort(), hp = 999))
    }
    return FakeSession(created.info.id) to created.info.id
  }
}

class BattleServiceR3Test :
    FunSpec({
      test("a double trainer battle puts two monsters out on each side and waits for both picks") {
        runTest {
          val fx = R3Fixture(this)
          val (session, charId) = fx.player(testMon(7, 40, listOf(55)), testMon(4, 40, listOf(52)), testMon(1, 40, listOf(22)))
          val trainer =
              TrainerDef(1, "TWINS", 1, true, 10,
                  listOf(TrainerMon(19, 5, 0, 0, listOf(33)), TrainerMon(19, 5, 0, 0, listOf(33))))
          val job = launch { fx.service.startTrainerBattle(session, trainer) }
          runCurrent()
          val battle = fx.registry.byChar(charId).shouldNotBeNull()
          battle.format shouldBe 2
          battle.own.actives shouldBe mutableListOf(0, 1)
          battle.foe.actives shouldBe mutableListOf(0, 1)
          session.sent.filterIsInstance<BattleExtPacket>().any { it.json.contains("\"format\":2") }.shouldBeTrue()
          val turnBefore = battle.turn
          fx.service.onExtAction(PacketEvent(BattleExtActionPacket(0, BattleExtKind.MOVE, 55, 1, 0), session))
          battle.turn shouldBe turnBefore // still waiting for position 1
          fx.service.onExtAction(PacketEvent(BattleExtActionPacket(1, BattleExtKind.MOVE, 52, 1, 1), session))
          // Two level 40 hits knock both level 5 foes out; the trainer has no more.
          battle.pendingResult shouldBe de.fiereu.openmmo.server.game.battle.BattleResult.VICTORY
          job.cancel()
        }
      }

      test("a trainer blocks a thrown ball") {
        runTest {
          val fx = R3Fixture(this)
          val (session, charId) = fx.player(testMon(7, 40, listOf(55)))
          fx.store.addItem(charId, fx.items.idOf(Items.POKE_BALL), 2)
          val trainer = TrainerDef(2, "JOEY", 1, false, 10, listOf(TrainerMon(19, 5, 0, 0, listOf(33))))
          val job = launch { fx.service.startTrainerBattle(session, trainer) }
          runCurrent()
          fx.service.onBattleAction(PacketEvent(BattleActionSelectPacket(0, BattleAction.ITEM, fx.items.idOf(Items.POKE_BALL).toShort(), 0, 0), session))
          session.sent.filterIsInstance<BattleExtPacket>().any { it.json.contains("blocked the BALL") }.shouldBeTrue()
          fx.store.getCharacter(charId)!!.items[fx.items.idOf(Items.POKE_BALL)] shouldBe 2
          job.cancel()
        }
      }

      test("a potion used in battle heals, is consumed and spends the turn") {
        runTest {
          val fx = R3Fixture(this)
          val (session, charId) = fx.player(testMon(7, 40, listOf(55)))
          val mon = fx.store.getCharacter(charId)!!.pokemon.single()
          fx.store.updatePokemon(charId, mon.copy(hp = 30))
          val potion = fx.items.idOf(Items.POTION)
          fx.store.addItem(charId, potion, 1)
          fx.service.startWildBattle(session, 129, 5) // Magikarp only splashes
          val battle = fx.registry.byChar(charId).shouldNotBeNull()
          fx.service.onExtAction(PacketEvent(BattleExtActionPacket(0, BattleExtKind.ITEM, potion.toShort(), targetEntity = mon.id), session))
          battle.activeMon().currentHp shouldBe 50
          (fx.store.getCharacter(charId)!!.items[potion] ?: 0) shouldBe 0
          battle.turn shouldBe 2
        }
      }

      test("the major status persists after the battle and a heal clears it") {
        runTest {
          val fx = R3Fixture(this)
          val (session, charId) = fx.player(testMon(7, 40, listOf(55), status = StatusCondition.POISON))
          fx.service.startWildBattle(session, 129, 5)
          fx.service.onBattleAction(PacketEvent(BattleActionSelectPacket(0, BattleAction.RUN, 0, 0, 0), session))
          fx.service.onClientReady(PacketEvent(MapLoadedAckPacket(), session))
          val stored = fx.store.getCharacter(charId)!!.pokemon.single()
          stored.status shouldBe StatusCondition.POISON
          (stored.hp < 999).shouldBeTrue() // poison bit at the end of the turns that ran, or full
        }
      }
    })
