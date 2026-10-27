package de.fiereu.openmmo.server.game.contest

import de.fiereu.openmmo.common.enums.PokemonNature
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

private const val POUND = 1
private const val SCRATCH = 10
private const val TACKLE = 33
private const val GROWL = 45
private const val THUNDER = 87
private const val RAIN_DANCE = 240

/** A repeatable RNG (an LCG like the GBA's) or a fixed list of draws. */
private class TestRng(seed: Int = 1) : ContestRng {
  private var s = seed.toLong()

  override fun next(): Int {
    s = (s * 1103515245L + 24691L) and 0xFFFFFFFFL
    return ((s ushr 16) and 0xFFFF).toInt()
  }
}

private class FixedRng(private val v: Int = 0) : ContestRng {
  override fun next() = v
}

private fun mon(
    name: String,
    moves: List<Int>,
    cool: Int = 0,
    sheen: Int = 0,
    ai: Long = 0,
) =
    ContestMon(
        25, name, "T$name", moves, cool, 0, 0, 0, 0, sheen, aiFlags = ai, isPlayer = ai == 0L)

/**
 * r5-contests: src/contest.c, contest_effect.c, contest_ai.c, berry.c, pokeblock*.c,
 * berry_blender.c.
 */
class ContestRulesTest :
    FunSpec({
      val data = ContestData.instance

      test("the decomp tables load: moves, effects, 96 opponents, AI program, 43 berries") {
        data.move(POUND).effect shouldBe 0
        data.effects[0].appeal shouldBe 40
        data.opponents.size shouldBe 96
        data.opponents.first().trainer shouldBe "JIMMY"
        data.aiTable.size shouldBe 32
        data.berries.size shouldBe 43
        data.berry(1).name shouldBe "CHERI"
        data.berryTrees.size shouldBeGreaterThan 80
        data.pokeblockNames[2] shouldBe "BLUE POKéBLOCK"
      }

      test(
          "round 1 points: main stat + (two neighbours + sheen) / 2 (CalculateContestantRound1Points)") {
            val m = ContestMon(25, "A", "T", listOf(POUND), 100, 40, 0, 0, 20, 60)
            val e = ContestEngine(Contest.COOL, 0, listOf(m, m, m, m), TestRng())
            // cool + (tough + beauty + sheen) / 2
            e.round1Points[0] shouldBe 100 + (20 + 40 + 60) / 2
            ContestEngine(Contest.BEAUTY, 0, listOf(m, m, m, m), TestRng()).round1Points[0] shouldBe
                40 + (100 + 0 + 60) / 2
          }

      test("the first round's order follows the condition round, highest first") {
        val mons =
            listOf(
                mon("A", listOf(POUND), cool = 10),
                mon("B", listOf(POUND), cool = 200),
                mon("C", listOf(POUND), cool = 50),
                mon("D", listOf(POUND), cool = 100))
        val e = ContestEngine(Contest.COOL, 0, mons, TestRng())
        e.turnOrder.toList() shouldBe listOf(3, 0, 2, 1)
      }

      test("Rain Dance then Thunder is a combo (AreMovesContestCombo)") {
        val e = ContestEngine(Contest.COOL, 0, List(4) { mon("x", listOf(POUND)) }, TestRng())
        e.areMovesCombo(RAIN_DANCE, THUNDER) shouldBe 1
        e.areMovesCombo(THUNDER, RAIN_DANCE) shouldBe 0
      }

      test("a plain appeal shows its hearts and the crowd cheers a matching move") {
        val mons = List(4) { mon("M$it", listOf(POUND, TACKLE, GROWL, SCRATCH)) }
        val e = ContestEngine(Contest.TOUGH, 0, mons, TestRng(3))
        val ev = e.playRound(mapOf(0 to 0, 1 to 0, 2 to 0, 3 to 0))
        // Pound: HIGHLY_APPEALING 40 appeal, a TOUGH move in a TOUGH contest excites the crowd
        val first = ev.first { it.kind == "hearts" }
        first.b shouldBe 40
        ev.any { it.kind == "crowd" && it.b == 1 } shouldBe true
        // +10 excitement bonus each, 4 appeals of 50 (the meter fills on the 5th)
        e.status.map { it.pointTotal }.toSet().size shouldNotBe 0
        e.appealNumber shouldBe 1
      }

      test("repeating a move: the judge is disappointed, -20 then -30 ((repeats + 1) * 10)") {
        val mons = List(4) { mon("M$it", listOf(POUND, SCRATCH)) }
        val e = ContestEngine(Contest.COOL, 0, mons, TestRng(5))
        e.playRound(mapOf(0 to 0, 1 to 1, 2 to 1, 3 to 1))
        val ev = e.playRound(mapOf(0 to 0, 1 to 1, 2 to 1, 3 to 1))
        ev.any { it.kind == "hearts" && it.who == 0 && it.b == -20 } shouldBe true
        val ev3 = e.playRound(mapOf(0 to 0, 1 to 0, 2 to 0, 3 to 0))
        ev3.any { it.kind == "hearts" && it.who == 0 && it.b == -30 } shouldBe true
      }

      test("a finished combo adds the move's base appeal again") {
        val mons = List(4) { mon("M$it", listOf(RAIN_DANCE, THUNDER)) }
        val e = ContestEngine(Contest.COOL, 0, mons, TestRng(7))
        e.playRound(mapOf(0 to 0, 1 to 0, 2 to 0, 3 to 0))
        e.status[0].hasJudgesAttention shouldBe true
        val ev = e.playRound(mapOf(0 to 1, 1 to 1, 2 to 1, 3 to 1))
        // Thunder: STARTLE_PREV_MONS (appeal 20), the combo bonus is baseAppeal * 1
        val base = data.effectOf(THUNDER).appeal
        ev.any { it.kind == "hearts" && it.who == 0 && it.b == base && it.a != 0 } shouldBe true
        ev.any { it.kind == "judge" && it.a == JudgeSymbol.TWO_EXCLAMATIONS } shouldBe true
      }

      test("startling the mon in front jams it by the effect's jam (WasAtLeastOneOpponentJammed)") {
        val startle = data.moves.entries.first { it.value.effect == 10 }.key
        val jam = data.effects[10].jam
        // the condition round puts A first, B second
        val mons =
            listOf(
                mon("A", listOf(POUND), cool = 200),
                mon("B", listOf(startle), cool = 150),
                mon("C", listOf(POUND), cool = 100),
                mon("D", listOf(POUND), cool = 50))
        val e = ContestEngine(Contest.COOL, 0, mons, TestRng(9))
        e.turnOrder[0] shouldBe 0
        e.turnOrder[1] shouldBe 1
        val ev = e.playRound(mapOf(0 to 0, 1 to 0, 2 to 0, 3 to 0))
        ev.any { it.kind == "hearts" && it.who == 0 && it.b == -jam } shouldBe true
        e.status[0].pointTotal shouldBe 40 - jam
      }

      test("NPC opponents pick legal moves with the AI scripts, a full contest ranks everyone") {
        val ops = data.opponents.filter { it.rank == 0 && it.pools[0] }.take(3)
        val mons =
            ops.map {
              ContestMon(
                  it.dex,
                  it.nickname,
                  it.trainer,
                  it.moves,
                  it.stats[0],
                  it.stats[1],
                  it.stats[2],
                  it.stats[3],
                  it.stats[4],
                  it.stats[5],
                  aiFlags = it.aiFlags)
            } + mon("PIKA", listOf(POUND, TACKLE, GROWL, SCRATCH), cool = 80, sheen = 30)
        val e = ContestEngine(Contest.COOL, 0, mons, TestRng(11))
        for (r in 0 until 5) e.playRound(mapOf(3 to r % 4))
        e.finished shouldBe true
        e.finalStandings.toList() shouldContainExactlyInAnyOrder listOf(0, 1, 2, 3)
        for (i in 0 until 4) e.totalPoints[i] shouldBe e.round1Points[i] + e.round2Points[i]
        for (i in 0 until 4) e.round2Points[i] shouldBe e.status[i].pointTotal * 2
        for (i in 0 until 3) for (h in e.moveHistory) (h[i] in ops[i].moves || h[i] == 0) shouldBe
            true
      }

      test("the AI never picks an empty move slot") {
        val op = data.opponents.first()
        val mons =
            listOf(
                ContestMon(
                    op.dex, "X", "Y", listOf(POUND), 0, 0, 0, 0, 0, 0, aiFlags = op.aiFlags)) +
                List(3) { mon("M$it", listOf(POUND)) }
        val e = ContestEngine(Contest.COOL, 0, mons, TestRng(13))
        repeat(20) { e.aiChoice(0) shouldBe 0 }
      }

      test(
          "feeding a Pokéblock: liked flavour boosted a tenth, sheen grows by feel (use_pokeblock.c)") {
            // Lonely likes spicy (+1) and dislikes sour (-1)
            val block = Pokeblock(1, 30, 0, 0, 0, 0, 20)
            val fed = PokeblockRules.feed(MonContest(), PokemonNature.LONELY, block)!!
            fed.cool shouldBe 33
            fed.sheen shouldBe 20
            PokeblockRules.gain(PokemonNature.LONELY, block) shouldBe 30
            // A neutral nature eats it plainly
            PokeblockRules.feed(MonContest(), PokemonNature.HARDY, block)!!.cool shouldBe 30
            // A maxed sheen refuses more
            PokeblockRules.feed(MonContest(sheen = 255), PokemonNature.HARDY, block) shouldBe null
          }

      test("CalculatePokeblock: Cheri + Chesto at 0 RPM makes a level 9 blue block, feel 23") {
        val b = BlenderEngine.calculatePokeblock(listOf(1, 2), 0, FixedRng())
        b.color shouldBe 2
        b.dry shouldBe 9
        b.spicy shouldBe 0
        b.feel shouldBe 23
        // the same berry twice is black
        BlenderEngine.calculatePokeblock(listOf(1, 1), 0, FixedRng()).color shouldBe Pokeblock.BLACK
      }

      test("the blender ends when the progress bar fills, NPCs hit the arrow") {
        val bl = BlenderEngine(2, listOf(0, 4), TestRng(17))
        var frames = 0
        while (!bl.finished && frames < 60 * 600) {
          bl.step(emptyMap())
          frames++
        }
        bl.finished shouldBe true
        bl.scores[1][0] shouldBeGreaterThan 0
        bl.maxRPM shouldBeGreaterThan 0
      }

      test("the blender judges a press by the arrow's proximity to the player's mark") {
        val bl = BlenderEngine(2, listOf(0, 1), TestRng(1))
        val start = data.arrowHitRangeStart[bl.arrowIdOf(0)]
        // p = pos/256 + 24 in [start+20, start+28) is BEST
        val best = (start + 20 - 24) * 256
        bl.scoreFor(best, 0) shouldBe BlenderEngine.SCORE_BEST
        bl.scoreFor((start + 2 - 24) * 256, 0) shouldBe BlenderEngine.SCORE_GOOD
        bl.scoreFor((start + 100) * 256, 0) shouldBe BlenderEngine.SCORE_MISS
      }

      test("NPC berries never repeat the player's own berry (sOpponentBerrySets)") {
        val opp = BlenderEngine.opponentBerries(1, 4, false)
        opp.size shouldBe 3
        (1 in opp) shouldBe false
      }

      test("a Cheri tree: a stage every 3 hours, berries after 4 stages, more water more berries") {
        val rng = TestRng(2)
        var t = BerryTreeRules.plant(1, BerryTree.STAGE_PLANTED, true, rng)
        t = BerryTreeRules.advance(t, 3 * 60, rng)
        t.stage shouldBe BerryTree.STAGE_SPROUTED
        t = BerryTreeRules.advance(t, 9 * 60, rng)
        t.stage shouldBe BerryTree.STAGE_BERRIES
        t.berryYield shouldBe data.berry(1).minYield
        var w = BerryTreeRules.plant(1, BerryTree.STAGE_PLANTED, true, rng)
        for (i in 0 until 4) {
          w = BerryTreeRules.water(w)!!
          w = BerryTreeRules.advance(w, 3 * 60, rng)
        }
        w.stage shouldBe BerryTree.STAGE_BERRIES
        w.berryYield shouldBe data.berry(1).maxYield
        // left alone far too long, the tree withers back to soil
        BerryTreeRules.advance(w, 3 * 60 * 71, rng) shouldBe BerryTree.BLANK
      }
    })
