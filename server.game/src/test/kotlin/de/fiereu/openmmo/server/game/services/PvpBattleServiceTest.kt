package de.fiereu.openmmo.server.game.services

import de.fiereu.network.PacketEvent
import de.fiereu.openmmo.common.enums.BattleAction
import de.fiereu.openmmo.common.enums.CharacterGender
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.moves.MoveRegistry
import de.fiereu.openmmo.net.game.packets.DuelChallengePacket
import de.fiereu.openmmo.net.game.packets.DuelInviteOutcomePacket
import de.fiereu.openmmo.net.game.packets.DuelInvitePacket
import de.fiereu.openmmo.net.game.packets.InGameChallengeResponsePacket
import de.fiereu.openmmo.net.game.packets.MapLoadedAckPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleActionSelectPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleBulkStatePacket
import de.fiereu.openmmo.net.game.packets.battle.BattleEntityMoveEventPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleFieldStatePacket
import de.fiereu.openmmo.net.game.packets.battle.OpposingSide
import de.fiereu.openmmo.pokemon.LearnsetRegistry
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import de.fiereu.openmmo.server.game.battle.BattlePacketEmitter
import de.fiereu.openmmo.server.game.battle.BattleRegistry
import de.fiereu.openmmo.server.game.battle.BattleResult
import de.fiereu.openmmo.server.game.battle.BattleRewards
import de.fiereu.openmmo.server.game.battle.BattleRng
import de.fiereu.openmmo.server.game.battle.MoveLearner
import de.fiereu.openmmo.server.game.battle.TurnEngine
import de.fiereu.openmmo.server.game.battle.WildMonFactory
import de.fiereu.openmmo.server.game.session.SessionRegistry
import de.fiereu.openmmo.server.game.storage.CharacterStore
import de.fiereu.openmmo.server.game.storage.EntityIdService
import de.fiereu.openmmo.server.game.testsupport.FakeCharacterRepository
import de.fiereu.openmmo.server.game.testsupport.FakeSession
import de.fiereu.openmmo.server.game.world.interest.InterestManager
import de.fiereu.openmmo.trainer.TrainerRegistry
import de.fiereu.openmmo.typechart.TypeChart
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

private class DuelFixture(scope: CoroutineScope) {
  val store = CharacterStore(FakeCharacterRepository(), EntityIdService(), scope)
  val interest = InterestManager()
  val registry = BattleRegistry()
  val sessions = SessionRegistry()
  val moves = MoveRegistry()
  val species = SpeciesRegistry()
  val wild = WildMonFactory(species, moves, LearnsetRegistry(), EntityIdService())
  val emitter = BattlePacketEmitter(interest)
  val pvp =
      PvpBattleService(
          store, sessions, registry, TurnEngine(moves, TypeChart()), emitter, interest, species)
  val battles =
      BattleService(
          characterStore = store,
          battles = registry,
          engine = TurnEngine(moves, TypeChart()),
          wildMons = wild,
          emitter = emitter,
          rewards = BattleRewards(),
          moveLearner = MoveLearner(LearnsetRegistry(), moves),
          interestManager = interest,
          speciesRegistry = species,
          moveRegistry = moves,
          trainers = TrainerRegistry(),
          items = ItemRegistry(),
          pvp = pvp,
      )

  suspend fun player(name: String, dexId: Int, level: Int): Pair<FakeSession, Long> {
    val created = store.createCharacter(1, name, CharacterGender.MALE, Region.KANTO)
    val id = created.info.id
    val mon = wild.create(dexId, level, BattleRng())!!
    store.addPokemon(id, mon.copy(ownerId = id, container = PokemonContainer.PARTY))
    val session = FakeSession(id)
    sessions.bindCharacter(session, id)
    return session to id
  }

  suspend fun duel(a: FakeSession, b: FakeSession, bName: String, aName: String) {
    pvp.onDuelChallenge(PacketEvent(challenge(bName), a))
    pvp.onChallengeResponse(PacketEvent(InGameChallengeResponsePacket(true, aName), b))
  }
}

private fun challenge(name: String) =
    DuelChallengePacket(name, 0, false, 0, 0, 0, 0, null, null, null, null)

private suspend fun FakeSession.act(service: BattleService, action: BattleAction, value: Short) {
  service.onBattleAction(PacketEvent(BattleActionSelectPacket(0, action, value, 0L, 0), this))
}

@OptIn(ExperimentalCoroutinesApi::class)
class PvpBattleServiceTest :
    FunSpec({
      test("an accepted challenge opens a linked battle for both players") {
        runTest {
          val fx = DuelFixture(backgroundScope)
          val (a, aId) = fx.player("Ash", 4, 20)
          val (b, bId) = fx.player("Gary", 7, 20)

          fx.duel(a, b, "Gary", "Ash")

          b.sent.filterIsInstance<DuelInvitePacket>().single().name shouldBe "Ash"
          a.sent.filterIsInstance<DuelInviteOutcomePacket>().single().packed shouldBe
              DuelOutcome.ACCEPTED
          val aBattle = fx.registry.byChar(aId).shouldNotBeNull()
          val bBattle = fx.registry.byChar(bId).shouldNotBeNull()
          (aBattle.pvp === bBattle.pvp) shouldBe true
          (aBattle.opponentMon() === bBattle.activeMon()) shouldBe true
          val aField = a.sent.filterIsInstance<BattleFieldStatePacket>().single()
          aField.opposing shouldBe OpposingSide.TRAINER
          aField.opponentParty.single().species shouldBe 7.toShort()
        }
      }

      test("a turn waits for both players, then both see it and nothing is persisted") {
        runTest {
          val fx = DuelFixture(backgroundScope)
          val (a, aId) = fx.player("Ash", 4, 20)
          val (b, bId) = fx.player("Gary", 7, 20)
          val hpBefore = fx.store.getCharacter(bId)!!.pokemon.single().hp
          fx.duel(a, b, "Gary", "Ash")
          val aMove = fx.registry.byChar(aId)!!.activeMon().moves.first().id
          val bMove = fx.registry.byChar(bId)!!.activeMon().moves.first().id

          a.act(fx.battles, BattleAction.MOVE, aMove)
          a.sent.none { it is BattleEntityMoveEventPacket } shouldBe true

          b.act(fx.battles, BattleAction.MOVE, bMove)
          a.sent.filterIsInstance<BattleEntityMoveEventPacket>().size shouldBe 2
          b.sent.filterIsInstance<BattleEntityMoveEventPacket>().size shouldBe 2

          // Ash forfeits, Gary wins, and Gary's monster keeps its pre-duel hp.
          a.act(fx.battles, BattleAction.RUN, 0)
          fx.registry.byChar(aId)!!.pendingResult shouldBe BattleResult.DEFEAT
          fx.registry.byChar(bId)!!.pendingResult shouldBe BattleResult.VICTORY
          b.sent.filterIsInstance<BattleBulkStatePacket>().single().flag shouldBe 2.toByte()
          fx.store.getCharacter(bId)!!.pokemon.single().hp shouldBe hpBefore

          fx.battles.onClientReady(PacketEvent(MapLoadedAckPacket(), a))
          fx.battles.onClientReady(PacketEvent(MapLoadedAckPacket(), b))
          fx.registry.byChar(aId).shouldBeNull()
          fx.registry.byChar(bId).shouldBeNull()
        }
      }

      test("a player who disconnects loses and the rival's battle ends") {
        runTest {
          val fx = DuelFixture(backgroundScope)
          val (a, aId) = fx.player("Ash", 4, 20)
          val (b, bId) = fx.player("Gary", 7, 20)
          fx.duel(a, b, "Gary", "Ash")

          fx.battles.onDisconnect(a)

          fx.registry.byChar(aId).shouldBeNull()
          fx.registry.byChar(bId)!!.pendingResult shouldBe BattleResult.VICTORY
          b.sent.filterIsInstance<BattleBulkStatePacket>().single().flag shouldBe 2.toByte()
        }
      }

      test("a declined challenge starts nothing") {
        runTest {
          val fx = DuelFixture(backgroundScope)
          val (a, aId) = fx.player("Ash", 4, 20)
          val (b, _) = fx.player("Gary", 7, 20)
          fx.pvp.onDuelChallenge(PacketEvent(challenge("Gary"), a))
          fx.pvp.onChallengeResponse(PacketEvent(InGameChallengeResponsePacket(false, "Ash"), b))

          a.sent.filterIsInstance<DuelInviteOutcomePacket>().single().packed shouldBe
              DuelOutcome.DECLINED
          fx.registry.byChar(aId).shouldBeNull()
        }
      }
    })
