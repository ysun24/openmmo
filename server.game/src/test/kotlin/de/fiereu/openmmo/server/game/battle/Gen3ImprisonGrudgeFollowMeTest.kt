package de.fiereu.openmmo.server.game.battle

import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.moves.MoveRegistry
import de.fiereu.openmmo.server.game.testsupport.FakeSession
import de.fiereu.openmmo.typechart.TypeChart
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private val engine4 = TurnEngine(MoveRegistry(), TypeChart(), ItemRegistry())

private const val SPLASH4 = 150
private const val TACKLE4 = 33
private const val GROWL4 = 45
private const val IMPRISON = 286
private const val GRUDGE = 288
private const val FOLLOW_ME = 266

/** r4: Imprison (Cmd_tryimprison / IsImprisoned), Grudge and Follow Me (Cmd_setforcedtarget). */
class Gen3ImprisonGrudgeFollowMeTest :
    FunSpec({
      fun single(me: BattleMonState, foe: BattleMonState) =
          BattleInstance(1L, 1L, FakeSession(1L), listOf(me), listOf(foe), BattleRng(5))

      fun msgs(ev: List<BattleEvent>) = ev.filterIsInstance<BattleEvent.Message>().map { it.text }

      test("Imprison seals the moves the user knows, and fails with nothing shared") {
        val user = stateOf(testMon(65, 60, listOf(IMPRISON, TACKLE4)))
        val foe = stateOf(testMon(19, 10, listOf(TACKLE4)), null)
        val b = single(user, foe)
        val ev =
            engine4.resolve(
                b,
                listOf(BattleChoice.UseMove(0, 0, IMPRISON), BattleChoice.UseMove(1, 0, TACKLE4)))
        user.volatile.imprisoning shouldBe true
        msgs(ev).any { "sealed the opponent" in it } shouldBe true
        msgs(ev).any { "can't use the sealed TACKLE" in it } shouldBe true
        user.currentHp shouldBe user.maxHp

        val user2 = stateOf(testMon(65, 60, listOf(IMPRISON)))
        val foe2 = stateOf(testMon(19, 10, listOf(GROWL4)), null)
        engine4.resolve(
            single(user2, foe2),
            listOf(BattleChoice.UseMove(0, 0, IMPRISON), BattleChoice.UseMove(1, 0, GROWL4)))
        user2.volatile.imprisoning shouldBe false
      }

      test("Grudge empties the PP of the move that knocks the user out") {
        val user = stateOf(testMon(291, 60, listOf(GRUDGE))) // Ninjask, faster
        val foe = stateOf(testMon(19, 10, listOf(TACKLE4)), null)
        user.currentHp = 1
        val ev =
            engine4.resolve(
                single(user, foe),
                listOf(BattleChoice.UseMove(0, 0, GRUDGE), BattleChoice.UseMove(1, 0, TACKLE4)))
        user.fainted shouldBe true
        foe.moves.first().pp.toInt() shouldBe 0
        msgs(ev).any { "lost all its PP due to the GRUDGE" in it } shouldBe true
      }

      test("Follow Me draws the foes' single-target moves in a double battle") {
        val user = stateOf(testMon(35, 60, listOf(FOLLOW_ME)), 0) // Clefairy, takes the hits
        val ally = stateOf(testMon(1, 50, listOf(SPLASH4)), 1)
        val f1 = stateOf(testMon(19, 20, listOf(TACKLE4)), null)
        val f2 = stateOf(testMon(19, 20, listOf(TACKLE4)), null)
        val b =
            BattleInstance(
                1L,
                1L,
                FakeSession(1L),
                listOf(user, ally),
                listOf(f1, f2),
                BattleRng(2),
                format = 2,
                own = BattleSideState(mutableListOf(0, 1)),
                foe = BattleSideState(mutableListOf(0, 1)),
                trainer = de.fiereu.openmmo.trainer.TrainerDef(1, "T", 1, true, 1, emptyList()))
        engine4.resolve(
            b,
            listOf(
                BattleChoice.UseMove(0, 0, FOLLOW_ME),
                BattleChoice.UseMove(0, 1, SPLASH4),
                BattleChoice.UseMove(1, 0, TACKLE4, BattlerRef(0, 1)),
                BattleChoice.UseMove(1, 1, TACKLE4, BattlerRef(0, 1))))
        ally.currentHp shouldBe ally.maxHp
        (user.currentHp < user.maxHp) shouldBe true
        user.volatile.followMe shouldBe true // until the next turn starts
      }
    })
