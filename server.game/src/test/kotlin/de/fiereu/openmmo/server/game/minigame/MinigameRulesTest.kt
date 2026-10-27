package de.fiereu.openmmo.server.game.minigame

import de.fiereu.openmmo.server.game.minigame.gamecorner.EmeraldSlotMachine
import de.fiereu.openmmo.server.game.minigame.gamecorner.FrlgSlotMachine
import de.fiereu.openmmo.server.game.minigame.gamecorner.Roulette
import de.fiereu.openmmo.server.game.minigame.link.BerryCrush
import de.fiereu.openmmo.server.game.minigame.link.DodrioBerryPicking
import de.fiereu.openmmo.server.game.minigame.link.LinkPlayer
import de.fiereu.openmmo.server.game.minigame.link.PokemonJump
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import kotlin.random.Random
import kotlinx.coroutines.test.runTest

/** r5-minigames: the decomp rules of the Game Corner and the link minigames. */
class MinigameRulesTest :
    FunSpec({
      fun rng(seed: Long): () -> Int {
        val r = Random(seed)
        return { r.nextInt(0x10000) }
      }

      test("FireRed CalcPayout pays the lines the bet lights (sRowAttributes, sPayoutTable)") {
        val m = FrlgSlotMachine(0) { 0 }
        // reel positions p show icons p+1..p+3; strip 0 index 0 is a 7 on every reel
        m.reelPositions[0] = 20
        m.reelPositions[1] = 20
        m.reelPositions[2] = 20
        m.addBet(1)
        m.calcPayout() shouldBe FrlgSlotMachine.PAYOUT_NONE // 7s are on the top row, bet 1 = middle
        m.addBet(2)
        m.calcPayout() shouldBe FrlgSlotMachine.PAYOUT_7
        // 300 for the 7s, plus the bottom row's two cherries (6) and the bl-tr line's cherry (2)
        m.payout shouldBe 308
        m.winFlags[1] shouldBe true // top row
      }

      test("FireRed cherries on the first reel pay alone (2) or with a second cherry (6)") {
        val m = FrlgSlotMachine(0) { 0 }
        // reel 0 index 2 is CHERRIES: position 0 shows indexes 1,2,3 -> middle = cherry
        m.reelPositions[0] = 0
        m.reelPositions[1] = 9 // middle index 11 = PSYDUCK
        m.reelPositions[2] = 0
        m.addBet(1)
        m.calcPayout() shouldBe FrlgSlotMachine.PAYOUT_CHERRIES2
        m.payout shouldBe 2
        m.reelPositions[1] = 0 // middle index 2 = CHERRIES
        m.calcPayout() shouldBe FrlgSlotMachine.PAYOUT_CHERRIES3
        m.payout shouldBe 6
      }

      test("FireRed spins always take coins out and pay at most the decomp's table") {
        val m = FrlgSlotMachine(5, rng(1))
        var paid = 0
        var won = 0
        repeat(2000) {
          m.addBet(3)
          m.spin() shouldBe true
          repeat(3) { r ->
            m.advance(7 + r)
            m.stopCurrentReel()
          }
          if (m.payout > 0) won++
          paid += m.payout
          m.payout shouldBeInRange 0..1500
        }
        won shouldBeGreaterThan 100
        paid shouldBeGreaterThan 0
      }

      test("Emerald CheckMatch: middle row at bet 1, top / bottom cherries pay 4, diagonals at 3") {
        val m = EmeraldSlotMachine(0, rng(2), lucky = false)
        // all three reels at 0 show symbols 1..3; LEFT index 1 is CHERRY
        m.addBet(1)
        m.reelPositions.fill(0)
        m.checkMatch()
        // middle row: LEFT[2]=AZURILL, MIDDLE[2]=REPLAY, RIGHT[2]=7 BLUE -> nothing
        m.payout shouldBe 0
        m.reelPositions[0] = 20 // left middle = CHERRY (index 1)
        m.checkMatch()
        m.payout shouldBe 2
        EmeraldSlotMachine.matchOf(0, 0, 1) shouldBe EmeraldSlotMachine.MATCH_MIXED_7
        EmeraldSlotMachine.matchOf(1, 1, 1) shouldBe EmeraldSlotMachine.MATCH_BLUE_7
        EmeraldSlotMachine.PAYOUTS[EmeraldSlotMachine.MATCH_RED_7] shouldBe 300
      }

      test("Emerald rounds pay only by sSlotPayouts and keep the bolts within 16") {
        val m = EmeraldSlotMachine(5, rng(3), lucky = true)
        var paid = 0
        repeat(3000) {
          if (!m.replay) m.addBet(3)
          m.spin()
          repeat(3) { m.stopCurrentReel() }
          m.payout shouldBeInRange 0..(5 * 300)
          paid += m.payout
        }
        paid shouldBeGreaterThan 0
        m.pikaPowerBolts shouldBeInRange 0..16
      }

      test("Emerald GetSlotMachineId follows the seed table") {
        EmeraldSlotMachine.machineIdFor(0, 0) shouldBe 0 // seed 12 % 12 = 0 -> UNLUCKIEST
        EmeraldSlotMachine.machineIdFor(4, 0) shouldBe 1 // seed 1 -> UNLUCKIER
        EmeraldSlotMachine.machineIdFor(1, 9) shouldBe 5 // 2 + 9 = 11 -> LUCKIEST
      }

      test("Roulette multipliers fall as squares are hit and a square pays minBet x 12") {
        val r = Roulette(0, false, rng(4))
        r.minBet shouldBe 1
        r.multiplier(6) shouldBe 12 // ORANGE WYNAUT
        r.multiplier(1) shouldBe 4 // WYNAUT column
        r.multiplier(5) shouldBe 3 // ORANGE row
        val t = r.throwBall(6, 0)
        t.square shouldBeInRange 6..19
        r.multiplier(t.square) shouldBe 0
        if (t.won) t.payout shouldBe 12
        repeat(5) { r.throwBall(1, it * 47) }
        r.roundOver shouldBe true
        r.hitSquares.toSet().size shouldBe 6 // a taken square is never hit twice
        Roulette(1, true) { 0 }.minBet shouldBe 6
      }

      test("Berry Crush: target = sum of difficulties, sync bonus, silkiness scales the powder") {
        val players = listOf(LinkPlayer("A", 1, 25), LinkPlayer("B", 2, 25))
        val g = BerryCrush(players, listOf(0, 6), Random(5)) // CHERI 50/20, ORAN 50/30
        g.target shouldBe 100
        while (!g.done) {
          g.press(0)
          g.press(1)
          g.tick()
          g.tick()
          g.tick()
        }
        // two players pressing together count 2 + 1 per frame
        g.presses[0] shouldBe 34
        g.syncPresses[0] shouldBe 34
        g.silkiness shouldBeInRange 50..100
        g.powder shouldBe 50 * 2 * g.silkiness / 100
      }

      test("Dodrio: scores 10/30/50, a miss costs 50, ten misses end the game") {
        val players = List(3) { LinkPlayer("N$it", null, 85) }
        val g = DodrioBerryPicking(players, Random(6))
        var n = 0
        while (!g.over && n++ < 200_000) g.tick()
        g.over shouldBe true
        g.graySquares shouldBe 10
        for (p in players.indices) {
          val s = g.picked[p][0] * 10 + g.picked[p][1] * 30 + g.picked[p][2] * 50 - g.picked[p][3] * 50
          g.score(p) shouldBe s.coerceAtLeast(0)
        }
        g.columns shouldBe 7
        g.reach(1).toList() shouldContain 3
      }

      test("Pokemon Jump: 10 a clear, bonus for jumping together, a trip ends it") {
        val players = listOf(LinkPlayer("A", 1, 4), LinkPlayer("B", 2, 4))
        val g = PokemonJump(players, listOf(PokemonJump.JUMP_FAST, PokemonJump.JUMP_FAST), Random(7))
        // both jump together for two swings, then nobody jumps
        repeat(2) {
          val start = g.frame
          while (g.frame - start < g.period / 2 - 20) g.tick()
          g.jump(0)
          g.jump(1)
          while (g.phase < 0.99 && !g.over) g.tick()
          while (g.phase > 0.5 && !g.over) g.tick()
        }
        g.jumpsInRow shouldBe 2
        g.score shouldBe 2 * (10 + 50)
        while (!g.over) g.tick()
        g.trippedBy shouldBe 0
        g.prizeQuantity() shouldBe 0
        PokemonJump.SPECIES[25] shouldBe PokemonJump.JUMP_NORMAL
      }

      test("coins stay within MAX_COINS and are persisted") {
        runTest {
          val repo = InMemoryMinigameRepository()
          val save = MinigameSave(repo)
          save.addCoins(1, 9990) shouldBe true
          save.addCoins(1, 10) shouldBe false
          save.addCoins(1, -9991) shouldBe false
          save.coins(1) shouldBe 9990
          repo.players[1]!!.coins shouldBe 9990
        }
      }
    })
