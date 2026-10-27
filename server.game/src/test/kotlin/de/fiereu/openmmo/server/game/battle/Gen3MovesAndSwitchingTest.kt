package de.fiereu.openmmo.server.game.battle

import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.moves.MoveRegistry
import de.fiereu.openmmo.progression.SpeciesWeights
import de.fiereu.openmmo.server.game.testsupport.FakeSession
import de.fiereu.openmmo.trainer.TrainerDef
import de.fiereu.openmmo.typechart.TypeChart
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

private val moves = MoveRegistry()
private val engine = TurnEngine(moves, TypeChart(), ItemRegistry())

private const val SPLASH = 150
private const val TACKLE = 33
private const val SKETCH = 166
private const val SWORDS_DANCE = 14
private const val SNATCH = 289
private const val MAGIC_COAT = 277
private const val TOXIC = 92
private const val THUNDER_WAVE = 86
private const val PERISH_SONG = 195
private const val LOW_KICK = 67

/** Round-3 integration: the moves the battle branch left failing, and trainers switching out. */
class Gen3MovesAndSwitchingTest :
    FunSpec({
      fun battle(
          me: BattleMonState,
          foes: List<BattleMonState>,
          trainer: Boolean = false,
          seed: Long = 3
      ) =
          BattleInstance(
              1L,
              1L,
              FakeSession(1L),
              listOf(me),
              foes,
              BattleRng(seed),
              trainer = if (trainer) TrainerDef(1, "T", 1, false, 1, emptyList()) else null)

      fun messages(events: List<BattleEvent>) =
          events.filterIsInstance<BattleEvent.Message>().map { it.text }

      test("Low Kick's power comes from the target's weight (sWeightToDamageTable)") {
        SpeciesWeights.hectograms(143) shouldBe 4600 // Snorlax 460 kg
        SpeciesWeights.hectograms(25) shouldBe 60 // Pikachu 6 kg
        lowKickPower(SpeciesWeights.hectograms(25)) shouldBe 20
        lowKickPower(SpeciesWeights.hectograms(4)) shouldBe 20 // Charmander 8.5 kg
        lowKickPower(SpeciesWeights.hectograms(1)) shouldBe 20
        lowKickPower(99) shouldBe 20
        lowKickPower(100) shouldBe 40
        lowKickPower(499) shouldBe 60
        lowKickPower(1999) shouldBe 100
        lowKickPower(SpeciesWeights.hectograms(143)) shouldBe 120
        moves.get(LOW_KICK)!!.effect shouldBe de.fiereu.openmmo.common.enums.MoveEffect.LOW_KICK
      }

      test("Sketch copies the target's last move for good") {
        val smeargle = stateOf(testMon(235, 30, listOf(SKETCH)))
        val foe = stateOf(testMon(19, 30, listOf(TACKLE)), null)
        val b = battle(smeargle, listOf(foe))
        foe.volatile.lastMove = TACKLE
        val ev =
            engine.resolve(
                b, listOf(BattleChoice.UseMove(0, 0, SKETCH), BattleChoice.UseMove(1, 0, SPLASH)))
        smeargle.moves.map { it.id.toInt() } shouldContain TACKLE
        messages(ev).any { "sketched" in it } shouldBe true
      }

      test("Snatch steals a self-targeting status move") {
        val thief = stateOf(testMon(52, 40, listOf(SNATCH)))
        val dancer = stateOf(testMon(19, 10, listOf(SWORDS_DANCE)), null)
        val b = battle(thief, listOf(dancer))
        val ev =
            engine.resolve(
                b,
                listOf(
                    BattleChoice.UseMove(0, 0, SNATCH), BattleChoice.UseMove(1, 0, SWORDS_DANCE)))
        messages(ev).any { "snatched" in it } shouldBe true
        thief.stage(BattleStat.ATTACK) shouldBe 2
        dancer.stage(BattleStat.ATTACK) shouldBe 0
      }

      test("Magic Coat bounces a status move back at its user") {
        val coat = stateOf(testMon(65, 40, listOf(MAGIC_COAT)))
        val foe = stateOf(testMon(19, 10, listOf(THUNDER_WAVE)), null)
        val b = battle(coat, listOf(foe))
        val ev =
            engine.resolve(
                b,
                listOf(
                    BattleChoice.UseMove(0, 0, MAGIC_COAT),
                    BattleChoice.UseMove(1, 0, THUNDER_WAVE)))
        messages(ev).any { "bounced back" in it } shouldBe true
        coat.paralyzed shouldBe false
        foe.paralyzed shouldBe true
      }

      test("a trainer switches out a monster whose moves cannot touch the foe") {
        // A Normal-type tackler against a Ghost: the trainer sends in the monster that can hit it.
        val ghost = stateOf(testMon(92, 30, listOf(SPLASH)))
        val normal = stateOf(testMon(19, 30, listOf(TACKLE)), 0)
        val fire = stateOf(testMon(4, 30, listOf(52)), 1)
        val b = battle(ghost, listOf(normal, fire), trainer = true)
        val ev = engine.resolve(b, listOf(BattleChoice.UseMove(0, 0, SPLASH)))
        b.foe.actives[0] shouldBe 1
        ev.filterIsInstance<BattleEvent.Switched>().any { it.newSlot == 1 } shouldBe true
      }

      test("Perish Song's last count makes a trainer switch; a wild monster stays") {
        val me = stateOf(testMon(19, 30, listOf(SPLASH)))
        val doomed = stateOf(testMon(19, 30, listOf(TACKLE)), 0)
        val other = stateOf(testMon(20, 30, listOf(TACKLE)), 1)
        val b = battle(me, listOf(doomed, other), trainer = true)
        doomed.volatile.perishCount = 1
        BattleAi.switchChoice(engine.context(b), BattlerRef(1, 0), { false }) shouldBe
            BattleChoice.Switch(1, 0, 1)
        val wild = battle(me, listOf(doomed))
        BattleAi.switchChoice(engine.context(wild), BattlerRef(1, 0), { false }) shouldBe null
        // Trapped monsters cannot switch.
        BattleAi.switchChoice(engine.context(b), BattlerRef(1, 0), { true }) shouldBe null
      }
    })
