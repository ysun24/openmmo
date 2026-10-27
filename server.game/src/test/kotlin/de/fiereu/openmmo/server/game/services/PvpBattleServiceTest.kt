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
import de.fiereu.openmmo.net.game.packets.battle.BattleTurnTimerPacket
import de.fiereu.openmmo.net.game.packets.battle.DuelRules
import de.fiereu.openmmo.net.game.packets.battle.DuelRulesChallengePacket
import de.fiereu.openmmo.net.game.packets.battle.DuelRulesPacket
import de.fiereu.openmmo.net.game.packets.battle.OpposingSide
import de.fiereu.openmmo.pokemon.LearnsetRegistry
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import de.fiereu.openmmo.server.game.battle.BattlePacketEmitter
import de.fiereu.openmmo.server.game.battle.BattleRegistry
import de.fiereu.openmmo.server.game.battle.BattleResult
import de.fiereu.openmmo.server.game.battle.BattleRewards
import de.fiereu.openmmo.server.game.battle.BattleRng
import de.fiereu.openmmo.server.game.battle.DuelTimer
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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

/** A turn clock the test fires by hand. */
private class ManualTimer : DuelTimer() {
  private val tasks = mutableListOf<Array<Any>>()

  override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
    val entry = arrayOf<Any>(false, task, delayMs)
    tasks += entry
    return { entry[0] = true }
  }

  val lastDelayMs: Long
    get() = tasks.last()[2] as Long

  /** Runs the newest timer that is still armed. */
  fun fire() {
    val live = tasks.last { !(it[0] as Boolean) }
    live[0] = true
    @Suppress("UNCHECKED_CAST") (live[1] as () -> Unit)()
  }
}

private class DuelFixture(scope: CoroutineScope) {
  val timer = ManualTimer()
  val store = CharacterStore(FakeCharacterRepository(), EntityIdService(), scope)
  val interest = InterestManager()
  val registry = BattleRegistry()
  val sessions = SessionRegistry()
  val moves = MoveRegistry()
  val species = SpeciesRegistry()
  val wild = WildMonFactory(species, moves, LearnsetRegistry(), EntityIdService())
  val emitter = BattlePacketEmitter(interest)
  val social = de.fiereu.openmmo.server.game.storage.InMemorySocialRepository()
  val blocks = de.fiereu.openmmo.server.game.services.social.BlockList(social)
  val pvp =
      PvpBattleService(
          store,
          sessions,
          registry,
          TurnEngine(moves, TypeChart()),
          emitter,
          interest,
          species,
          timer,
          blocks)
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

  suspend fun player(
      name: String,
      dexId: Int,
      level: Int,
      count: Int = 1
  ): Pair<FakeSession, Long> {
    val created = store.createCharacter(1, name, CharacterGender.MALE, Region.KANTO)
    val id = created.info.id
    repeat(count) { i ->
      val mon = wild.create(dexId, level, BattleRng())!!
      store.addPokemon(
          id,
          mon.copy(ownerId = id, container = PokemonContainer.PARTY, containerSlot = i.toShort()))
    }
    val session = FakeSession(id)
    sessions.bindCharacter(session, id)
    return session to id
  }

  suspend fun duel(a: FakeSession, b: FakeSession, bName: String, aName: String) {
    pvp.onDuelChallenge(PacketEvent(challenge(bName), a))
    pvp.onChallengeResponse(PacketEvent(InGameChallengeResponsePacket(true, aName), b))
  }
}

private fun rulesChallenge(name: String, rules: DuelRules) = DuelRulesChallengePacket(name, rules)

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

      test("a player who blocked you cannot be challenged, nor can one you blocked") {
        runTest {
          val fx = DuelFixture(backgroundScope)
          val (a, aId) = fx.player("Ash", 4, 20)
          val (b, bId) = fx.player("Gary", 7, 20)
          fx.social.register(aId, "Ash")
          fx.social.register(bId, "Gary")
          fx.social.block(bId, aId, java.time.LocalDateTime.now())
          fx.pvp.onDuelChallenge(PacketEvent(challenge("Gary"), a))
          a.sent.filterIsInstance<DuelInviteOutcomePacket>().single().packed shouldBe
              DuelOutcome.UNAVAILABLE
          b.sent.filterIsInstance<DuelRulesPacket>().shouldBeEmpty()
          fx.pvp.onDuelChallenge(PacketEvent(challenge("Ash"), b))
          b.sent.filterIsInstance<DuelInviteOutcomePacket>().single().packed shouldBe
              DuelOutcome.UNAVAILABLE
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

      test("the invite carries the rules and a timeout makes the server pick the first move") {
        runTest {
          val fx = DuelFixture(backgroundScope)
          val (a, aId) = fx.player("Ash", 4, 20)
          val (b, bId) = fx.player("Gary", 7, 20)
          val rules = DuelRules(levelCap = 0, maxParty = 6, turnSeconds = 30)
          fx.pvp.onRulesChallenge(PacketEvent(rulesChallenge("Gary", rules), a))
          b.sent.filterIsInstance<DuelRulesPacket>().single() shouldBe DuelRulesPacket("Ash", rules)
          fx.pvp.onChallengeResponse(PacketEvent(InGameChallengeResponsePacket(true, "Ash"), b))
          a.sent.filterIsInstance<DuelRulesPacket>().single().challenger shouldBe "Gary"
          fx.timer.lastDelayMs shouldBe 30_000L
          a.sent.filterIsInstance<BattleTurnTimerPacket>().last().seconds shouldBe 30

          // Ash picks, Gary idles: the clock runs out and Gary's first usable move is used.
          val aMove = fx.registry.byChar(aId)!!.activeMon().moves.first().id
          val bFirst = fx.registry.byChar(bId)!!.activeMon().moves.first { it.pp > 0 }.id
          a.act(fx.battles, BattleAction.MOVE, aMove)
          fx.timer.fire()

          val moves = b.sent.filterIsInstance<BattleEntityMoveEventPacket>()
          moves.size shouldBe 2
          moves
              .single { it.sourceEntity == fx.registry.byChar(bId)!!.activeMon().entityId }
              .sourceMove shouldBe bFirst
          val auto = b.sent.filterIsInstance<BattleTurnTimerPacket>().first { it.autoPicked }
          auto.timeouts shouldBe 1
          // The next turn re-arms the clock and both sides see Gary's count.
          a.sent.filterIsInstance<BattleTurnTimerPacket>().last().rivalTimeouts shouldBe 1
          fx.registry.byChar(aId)!!.pendingResult.shouldBeNull()
        }
      }

      test("three timeouts forfeit the duel and nothing is persisted") {
        runTest {
          val fx = DuelFixture(backgroundScope)
          // Bulky monsters at a high level so three turns cannot end it.
          val (a, aId) = fx.player("Ash", 143, 60)
          val (b, bId) = fx.player("Gary", 143, 60)
          val hpBefore = fx.store.getCharacter(bId)!!.pokemon.single().hp
          fx.pvp.onRulesChallenge(
              PacketEvent(rulesChallenge("Gary", DuelRules(turnSeconds = 30)), a))
          fx.pvp.onChallengeResponse(PacketEvent(InGameChallengeResponsePacket(true, "Ash"), b))
          repeat(2) {
            a.act(
                fx.battles,
                BattleAction.MOVE,
                fx.registry.byChar(aId)!!.activeMon().moves.first().id)
            fx.timer.fire()
            fx.registry.byChar(bId)!!.pendingResult.shouldBeNull()
          }
          a.act(
              fx.battles, BattleAction.MOVE, fx.registry.byChar(aId)!!.activeMon().moves.first().id)
          fx.timer.fire()

          fx.registry.byChar(bId)!!.pendingResult shouldBe BattleResult.DEFEAT
          fx.registry.byChar(aId)!!.pendingResult shouldBe BattleResult.VICTORY
          fx.store.getCharacter(bId)!!.pokemon.single().hp shouldBe hpBefore
        }
      }

      test("both sides idling counts a timeout for each and still resolves the turn") {
        runTest {
          val fx = DuelFixture(backgroundScope)
          val (a, _) = fx.player("Ash", 143, 60)
          val (b, _) = fx.player("Gary", 143, 60)
          fx.pvp.onRulesChallenge(
              PacketEvent(rulesChallenge("Gary", DuelRules(turnSeconds = 60)), a))
          fx.pvp.onChallengeResponse(PacketEvent(InGameChallengeResponsePacket(true, "Ash"), b))
          fx.timer.fire()
          a.sent.filterIsInstance<BattleEntityMoveEventPacket>().size shouldBe 2
          val t = a.sent.filterIsInstance<BattleTurnTimerPacket>().last()
          t.timeouts shouldBe 1
          t.rivalTimeouts shouldBe 1
        }
      }

      test("the level cap scales monsters down for the duel only") {
        runTest {
          val fx = DuelFixture(backgroundScope)
          val (a, aId) = fx.player("Ash", 4, 80)
          val (b, bId) = fx.player("Gary", 7, 30)
          val storedBefore = fx.store.getCharacter(aId)!!.pokemon.single()
          fx.pvp.onRulesChallenge(PacketEvent(rulesChallenge("Gary", DuelRules(levelCap = 50)), a))
          fx.pvp.onChallengeResponse(PacketEvent(InGameChallengeResponsePacket(true, "Ash"), b))

          val mine = fx.registry.byChar(aId)!!.activeMon()
          mine.level shouldBe 50
          (mine.stats.hp < storedBefore.hp) shouldBe true
          mine.currentHp shouldBe mine.stats.hp
          // Below the cap nothing changes.
          fx.registry.byChar(bId)!!.activeMon().level shouldBe 30
          b.sent
              .filterIsInstance<BattleFieldStatePacket>()
              .single()
              .opponentParty
              .single()
              .level shouldBe 50.toByte()

          a.act(fx.battles, BattleAction.RUN, 0)
          fx.store.getCharacter(aId)!!.pokemon.single() shouldBe storedBefore
        }
      }

      test("the party size limit brings only the first monsters") {
        runTest {
          val fx = DuelFixture(backgroundScope)
          val (a, aId) = fx.player("Ash", 4, 20, count = 4)
          val (b, bId) = fx.player("Gary", 7, 20, count = 2)
          fx.pvp.onRulesChallenge(PacketEvent(rulesChallenge("Gary", DuelRules(maxParty = 1)), a))
          fx.pvp.onChallengeResponse(PacketEvent(InGameChallengeResponsePacket(true, "Ash"), b))

          fx.registry.byChar(aId)!!.party.size shouldBe 1
          fx.registry.byChar(bId)!!.party.size shouldBe 1
          a.sent.filterIsInstance<BattleFieldStatePacket>().single().playerParty.size shouldBe 1
          b.sent.filterIsInstance<BattleFieldStatePacket>().single().opponentParty.size shouldBe 1
          // Switching is refused, there is nobody to switch to.
          b.act(fx.battles, BattleAction.SWITCH, 1)
          b.sent.filterIsInstance<BattleEntityMoveEventPacket>().size shouldBe 0
        }
      }

      test("a duel without a timer arms no clock") {
        runTest {
          val fx = DuelFixture(backgroundScope)
          val (a, _) = fx.player("Ash", 4, 20)
          val (b, _) = fx.player("Gary", 7, 20)
          fx.duel(a, b, "Gary", "Ash")
          a.sent.filterIsInstance<BattleTurnTimerPacket>().size shouldBe 0
        }
      }
    })
