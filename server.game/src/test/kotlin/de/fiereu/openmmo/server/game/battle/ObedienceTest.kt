package de.fiereu.openmmo.server.game.battle

import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.moves.MoveRegistry
import de.fiereu.openmmo.server.game.testsupport.FakeSession
import de.fiereu.openmmo.typechart.TypeChart
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

private val engine = TurnEngine(MoveRegistry(), TypeChart(), ItemRegistry())
private const val TACKLE = 33
private const val SPLASH = 150

private val DISOBEY =
    listOf(
        "is loafing around!",
        "won't obey!",
        "turned away!",
        "pretended not to notice!",
        "ignored orders",
        "began to nap!")

/** battle_util.c IsMonDisobedient: traded monsters above the badge level may not listen. */
class ObedienceTest :
    FunSpec({
      fun battle(level: Int, ot: String, obedience: Int, seed: Long): BattleInstance {
        val me = stateOf(testMon(19, level, listOf(TACKLE)).copy(ot = ot))
        val foe = stateOf(testMon(143, 100, listOf(SPLASH)), null)
        return BattleInstance(1L, 1L, FakeSession(1L), listOf(me), listOf(foe), BattleRng(seed))
            .also {
              it.trainerName = "Ash"
              it.obedienceLevel = obedience
            }
      }

      fun disobeyed(b: BattleInstance): Boolean =
          engine
              .resolve(
                  b, listOf(BattleChoice.UseMove(0, 0, TACKLE), BattleChoice.UseMove(1, 0, SPLASH)))
              .filterIsInstance<BattleEvent.Message>()
              .any { m -> DISOBEY.any { it in m.text } }

      test("an own monster always obeys, whatever its level") {
        (1L..60L).count { disobeyed(battle(90, "Ash", 10, it)) } shouldBe 0
      }

      test("a traded monster at or below the badge level obeys") {
        (1L..60L).count { disobeyed(battle(30, "Gary", 30, it)) } shouldBe 0
      }

      test("a traded monster far above the badge level often disobeys; eight badges stop it") {
        // Level 90 with no badges: it obeys only when (90+10)*rnd>>8 < 10, about 1 in 10.
        (1L..60L).count { disobeyed(battle(90, "Gary", 10, it)) } shouldBeGreaterThan 30
        (1L..60L).count { disobeyed(battle(90, "Gary", 100, it)) } shouldBe 0
      }
    })
