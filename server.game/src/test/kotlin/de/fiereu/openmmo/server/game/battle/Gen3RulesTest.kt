package de.fiereu.openmmo.server.game.battle

import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.PokemonMove
import de.fiereu.openmmo.common.StatusCondition
import de.fiereu.openmmo.common.enums.Ability
import de.fiereu.openmmo.common.enums.EVs
import de.fiereu.openmmo.common.enums.IVs
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.moves.MoveRegistry
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import de.fiereu.openmmo.server.game.testsupport.FakeSession
import de.fiereu.openmmo.typechart.TypeChart
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.LocalDateTime

private val speciesReg = SpeciesRegistry()
private val moveReg = MoveRegistry()
private val itemReg = ItemRegistry()
private val engine = TurnEngine(moveReg, TypeChart(), itemReg)

// Move ids (Gen 3 / national numbering).
private const val TACKLE = 33
private const val SPLASH = 150
private const val EMBER = 52
private const val TOXIC = 92
private const val THUNDER_WAVE = 86
private const val WILL_O_WISP = 261
private const val HYPNOSIS = 95
private const val EARTHQUAKE = 89
private const val FISSURE = 90
private const val THUNDERSHOCK = 84
private const val FAKE_OUT = 252
private const val CONFUSE_RAY = 109
private const val RAIN_DANCE = 240
private const val FLAMETHROWER = 53
private const val SURF = 57
private const val QUICK_ATTACK = 98
private const val VINE_WHIP = 22
private const val PROTECT = 182

private var nextId = 1000L

internal fun testMon(
    dex: Int,
    level: Int,
    moves: List<Int>,
    seed: Int = 0,
    status: Int = 0,
    held: Int = 0,
    friendship: Int = 70,
): Pokemon =
    Pokemon(
        id = nextId++,
        ownerId = 1,
        container = PokemonContainer.PARTY,
        containerSlot = 0,
        dexId = dex,
        seed = seed,
        ot = "Ash",
        nickname = "",
        level = level.toByte(),
        hp = Short.MAX_VALUE,
        xp = 0,
        eVs = EVs(),
        iVs = IVs(),
        moves =
            List(4) { i ->
              val id = moves.getOrNull(i) ?: 0
              PokemonMove(id.toShort(), (moveReg.get(id)?.pp ?: 0).toByte())
            },
        isShiny = false,
        hasHiddenAbility = false,
        isAlpha = false,
        isSecret = false,
        isFatefulEncounter = false,
        isRaidEncounter = false,
        caughtAt = LocalDateTime.now(),
        status = status,
        heldItem = held,
        friendship = friendship,
    )

internal fun stateOf(mon: Pokemon, index: Int? = 0): BattleMonState {
  val def = speciesReg.get(mon.dexId)!!
  return BattleMonState(mon.id, def, index, mon, StatCalculator.computeAll(def, mon))
}

private fun single(
    me: BattleMonState,
    foe: BattleMonState,
    seed: Long = 1,
    trainer: Boolean = false
) =
    BattleInstance(
        1L,
        1L,
        FakeSession(1L),
        listOf(me),
        listOf(foe),
        BattleRng(seed),
        trainer =
            if (trainer) de.fiereu.openmmo.trainer.TrainerDef(1, "T", 1, false, 1, emptyList())
            else null)

private fun doubles(me: List<BattleMonState>, foe: List<BattleMonState>, seed: Long = 1) =
    BattleInstance(
        1L,
        1L,
        FakeSession(1L),
        me,
        foe,
        BattleRng(seed),
        format = 2,
        own = BattleSideState(mutableListOf(0, 1)),
        foe = BattleSideState(mutableListOf(0, 1)),
        trainer = de.fiereu.openmmo.trainer.TrainerDef(1, "T", 1, true, 1, emptyList()))

private fun turn(b: BattleInstance, mine: Int, foes: Int = SPLASH) =
    engine.resolve(b, listOf(BattleChoice.UseMove(0, 0, mine), BattleChoice.UseMove(1, 0, foes)))

private fun wire(item: de.fiereu.openmmo.items.ItemDef) = itemReg.idOf(item)

class Gen3RulesTest :
    FunSpec({
      // --- major status ------------------------------------------------------------------------

      test("burn costs an eighth at the end of the turn and halves physical damage") {
        val burned = stateOf(testMon(4, 30, listOf(TACKLE), status = StatusCondition.BURN))
        val foe = stateOf(testMon(19, 30, listOf(SPLASH)), null)
        val b = single(burned, foe)
        turn(b, SPLASH)
        burned.currentHp shouldBe burned.maxHp - burned.maxHp / 8

        // Same seed, same attacker, with and without the burn.
        fun dealt(status: Int): Int {
          val a = stateOf(testMon(4, 30, listOf(TACKLE), status = status))
          val d = stateOf(testMon(19, 30, listOf(SPLASH)), null)
          val runner = MoveRunner(engine.context(single(a, d, seed = 5)))
          return runner
              .damage(a, d, moveReg.get(TACKLE)!!, 35, moveReg.get(TACKLE)!!.type, false, false)
              .damage
        }
        dealt(StatusCondition.BURN) shouldBeLessThan dealt(0)
      }

      test("poison costs an eighth, bad poison n/16 growing each turn") {
        val p = stateOf(testMon(1, 30, listOf(SPLASH), status = StatusCondition.TOXIC))
        val foe = stateOf(testMon(19, 30, listOf(SPLASH)), null)
        val b = single(p, foe)
        val max = p.maxHp
        turn(b, SPLASH)
        p.currentHp shouldBe max - max * 1 / 16
        turn(b, SPLASH)
        p.currentHp shouldBe max - max * 1 / 16 - max * 2 / 16
      }

      test("poison and steel types can't be poisoned, fire can't be burned") {
        val user = stateOf(testMon(92, 30, listOf(TOXIC)))
        val ekans = stateOf(testMon(23, 30, listOf(SPLASH)), null)
        val ctx = engine.context(single(user, ekans))
        ctx.trySetStatus(ekans, StatusCondition.TOXIC, user, true).shouldBeFalse()
        val charmander = stateOf(testMon(4, 30, listOf(SPLASH)), null)
        ctx.trySetStatus(charmander, StatusCondition.BURN, user, true).shouldBeFalse()
        ctx.trySetStatus(charmander, StatusCondition.PARALYSIS, user, true).shouldBeTrue()
        // A monster with a major status can't take a second one.
        ctx.trySetStatus(charmander, StatusCondition.POISON, user, true).shouldBeFalse()
      }

      test("paralysis quarters speed and Thunder Wave can't hit a ground type") {
        val pikachu = stateOf(testMon(25, 30, listOf(THUNDER_WAVE)))
        val geodude = stateOf(testMon(74, 30, listOf(SPLASH)), null)
        val b = single(pikachu, geodude)
        val ctx = engine.context(b)
        val speed = ctx.speedOf(pikachu)
        pikachu.status = StatusCondition.PARALYSIS
        ctx.speedOf(pikachu) shouldBe speed / 4
        pikachu.status = 0
        turn(b, THUNDER_WAVE)
        geodude.paralyzed.shouldBeFalse()
      }

      test("sleep lasts 2-5 turns and the monster acts on the turn it wakes") {
        val ctx =
            engine.context(
                single(
                    stateOf(testMon(1, 30, listOf(SPLASH))),
                    stateOf(testMon(19, 30, listOf(SPLASH)), null)))
        repeat(20) { i ->
          val m = stateOf(testMon(19, 30, listOf(TACKLE)), null)
          val ok =
              BattleCtx(
                  single(stateOf(testMon(1, 30, listOf(SPLASH))), m, seed = i.toLong()),
                  moveReg,
                  TypeChart(),
                  HeldItems(itemReg))
          ok.trySetStatus(m, StatusCondition.SLEEP_MASK, null, false).shouldBeTrue()
          (m.status and StatusCondition.SLEEP_MASK) shouldBeInRange 2..5
        }
        ctx.battle.party.size shouldBe 1
        val sleeper = stateOf(testMon(19, 30, listOf(TACKLE), status = 1))
        val target = stateOf(testMon(19, 30, listOf(SPLASH)), null)
        val events = turn(single(sleeper, target), TACKLE)
        sleeper.asleep.shouldBeFalse()
        events
            .filterIsInstance<BattleEvent.Message>()
            .any { it.text.endsWith("woke up!") }
            .shouldBeTrue()
        events
            .filterIsInstance<BattleEvent.MoveUsed>()
            .any { it.attackerId == sleeper.entityId }
            .shouldBeTrue()
      }

      test("a frozen monster stays frozen until thawed by a fire hit") {
        val frozen = stateOf(testMon(19, 30, listOf(SPLASH), status = StatusCondition.FREEZE), null)
        val charmander = stateOf(testMon(4, 30, listOf(EMBER)))
        val b = single(charmander, frozen, seed = 3)
        turn(b, EMBER)
        frozen.frozen.shouldBeFalse()
      }

      test("confusion lasts 2-5 turns and Own Tempo prevents it") {
        val user = stateOf(testMon(92, 30, listOf(CONFUSE_RAY)))
        val target = stateOf(testMon(19, 30, listOf(SPLASH)), null)
        val ctx = engine.context(single(user, target))
        ctx.tryConfuse(target, user, true).shouldBeTrue()
        target.volatile.confusionTurns shouldBeInRange 2..5
        val slowpoke = stateOf(testMon(79, 30, listOf(SPLASH), seed = 1), null) // Own Tempo
        slowpoke.ability shouldBe Ability.OWN_TEMPO
        ctx.tryConfuse(slowpoke, user, true).shouldBeFalse()
      }

      test("a flinch only stops a monster that has not moved yet (Fake Out)") {
        val fast = stateOf(testMon(52, 30, listOf(FAKE_OUT))) // Meowth
        val slow = stateOf(testMon(74, 30, listOf(TACKLE)), null)
        val events = turn(single(fast, slow), FAKE_OUT, TACKLE)
        events
            .filterIsInstance<BattleEvent.Message>()
            .any { it.text.endsWith("flinched!") }
            .shouldBeTrue()
        // Fake Out fails after the first turn out.
        val again =
            turn(
                single(fast, slow).also { fast.volatile.actedSinceSwitchIn = true },
                FAKE_OUT,
                SPLASH)
        again
            .filterIsInstance<BattleEvent.MoveFailed>()
            .any { it.attackerId == fast.entityId }
            .shouldBeTrue()
      }

      test("secondary effects roll their chance: Ember burns about 10% of the time") {
        var burns = 0
        repeat(400) { s ->
          val me = stateOf(testMon(4, 50, listOf(EMBER)))
          val foe = stateOf(testMon(1, 50, listOf(SPLASH)), null)
          turn(single(me, foe, seed = s.toLong()), EMBER)
          if (foe.burned) burns++
        }
        burns shouldBeInRange 15..75
      }

      // --- abilities ---------------------------------------------------------------------------

      test("Intimidate lowers the foe's attack on entry") {
        val gyarados = stateOf(testMon(130, 30, listOf(SPLASH)), null)
        val me = stateOf(testMon(4, 30, listOf(SPLASH)))
        engine.startOfBattle(single(me, gyarados))
        me.stage(BattleStat.ATTACK) shouldBe -1
      }

      test("Levitate makes ground moves miss, Sturdy stops one-hit KOs") {
        val gastly = stateOf(testMon(92, 30, listOf(SPLASH)), null)
        val user = stateOf(testMon(74, 30, listOf(EARTHQUAKE)))
        turn(single(user, gastly), EARTHQUAKE)
        gastly.currentHp shouldBe gastly.maxHp
        val sturdy = stateOf(testMon(74, 20, listOf(SPLASH), seed = 1), null)
        sturdy.ability shouldBe Ability.STURDY
        val digger = stateOf(testMon(50, 40, listOf(FISSURE)))
        repeat(5) { turn(single(digger, sturdy, seed = it.toLong()), FISSURE) }
        sturdy.fainted.shouldBeFalse()
      }

      test("Volt Absorb heals instead of taking an electric hit") {
        val lanturn = stateOf(testMon(171, 30, listOf(SPLASH)), null)
        lanturn.currentHp = lanturn.maxHp / 2
        val pikachu = stateOf(testMon(25, 30, listOf(THUNDERSHOCK)))
        turn(single(pikachu, lanturn, seed = 2), THUNDERSHOCK)
        lanturn.currentHp shouldBeGreaterThan lanturn.maxHp / 2
      }

      test("Static paralyzes contact attackers about a third of the time") {
        var paralyzed = 0
        repeat(150) { s ->
          val pikachu = stateOf(testMon(25, 50, listOf(SPLASH)), null)
          val user = stateOf(testMon(19, 50, listOf(TACKLE)))
          turn(single(user, pikachu, seed = s.toLong()), TACKLE)
          if (user.paralyzed) paralyzed++
        }
        paralyzed shouldBeInRange 25..80
      }

      test("Swift Swim doubles speed in rain; Overgrow boosts grass moves in a pinch") {
        val lotad = stateOf(testMon(270, 30, listOf(SPLASH)))
        val foe = stateOf(testMon(19, 30, listOf(SPLASH)), null)
        val b = single(lotad, foe)
        val ctx = engine.context(b)
        val dry = ctx.speedOf(lotad)
        b.field.weather = Weather.RAIN
        ctx.speedOf(lotad) shouldBe dry * 2

        val bulba = stateOf(testMon(1, 30, listOf(VINE_WHIP)))
        val target = stateOf(testMon(19, 30, listOf(SPLASH)), null)
        val runner = MoveRunner(engine.context(single(bulba, target, seed = 4)))
        val vine = moveReg.get(VINE_WHIP)!!
        val full = runner.damage(bulba, target, vine, vine.power, vine.type, false, false).damage
        bulba.currentHp = 1
        val runner2 = MoveRunner(engine.context(single(bulba, target, seed = 4)))
        runner2
            .damage(bulba, target, vine, vine.power, vine.type, false, false)
            .damage shouldBeGreaterThan full
      }

      // --- held items --------------------------------------------------------------------------

      test("Leftovers restore a sixteenth each turn; an Oran Berry fires at half hp") {
        val holder = stateOf(testMon(1, 30, listOf(SPLASH), held = wire(Items.LEFTOVERS)))
        holder.currentHp = holder.maxHp / 2
        val foe = stateOf(testMon(19, 30, listOf(SPLASH)), null)
        turn(single(holder, foe), SPLASH)
        holder.currentHp shouldBe holder.maxHp / 2 + holder.maxHp / 16

        val berry = stateOf(testMon(1, 30, listOf(SPLASH), held = wire(Items.ORAN_BERRY)))
        val ctx = engine.context(single(berry, foe))
        berry.currentHp = berry.maxHp / 2
        ctx.pinchBerry(berry)
        berry.currentHp shouldBe berry.maxHp / 2 + 10
        berry.heldItem shouldBe 0
      }

      test("a Lum Berry cures a status the moment it lands") {
        val holder = stateOf(testMon(1, 30, listOf(SPLASH), held = wire(Items.LUM_BERRY)), null)
        val user = stateOf(testMon(92, 30, listOf(HYPNOSIS)))
        val ctx = engine.context(single(user, holder))
        ctx.trySetStatus(holder, StatusCondition.PARALYSIS, user, true).shouldBeTrue()
        holder.hasStatus.shouldBeFalse()
        holder.heldItem shouldBe 0
      }

      test("Choice Band raises physical damage by half and type boosters by a tenth") {
        fun dmg(held: Int): Int {
          val a = stateOf(testMon(19, 40, listOf(TACKLE), held = held))
          val d = stateOf(testMon(1, 40, listOf(SPLASH)), null)
          val r = MoveRunner(engine.context(single(a, d, seed = 9)))
          val m = moveReg.get(TACKLE)!!
          return r.damage(a, d, m, m.power, m.type, false, false).damage
        }
        dmg(wire(Items.CHOICE_BAND)) shouldBeGreaterThan dmg(0)
        dmg(wire(Items.SILK_SCARF)) shouldBeGreaterThan dmg(0) - 1
      }

      test("Quick Claw sometimes lets a slower holder move first") {
        var firsts = 0
        repeat(200) { s ->
          val slow = stateOf(testMon(74, 20, listOf(TACKLE), held = wire(Items.QUICK_CLAW)))
          val fast = stateOf(testMon(19, 40, listOf(TACKLE)), null)
          val ev = turn(single(slow, fast, seed = s.toLong()), TACKLE, TACKLE)
          if (ev.filterIsInstance<BattleEvent.MoveUsed>().firstOrNull()?.attackerId ==
              slow.entityId)
              firsts++
        }
        firsts shouldBeInRange 15..80
      }

      // --- catching -----------------------------------------------------------------------------

      test("the catch formula follows Cmd_handleballthrow") {
        val ctx = CatchContext(turn = 1, underwater = false, alreadyCaught = false)
        val wild = stateOf(testMon(19, 10, listOf(TACKLE)), null) // catch rate 255
        // Full hp: catchRate * ball/10 * (3max - 2hp) / 3max = 255 * 1/3 = 85.
        CatchCalculator.odds(Items.POKE_BALL, wild, ctx) shouldBe 85
        CatchCalculator.odds(Items.GREAT_BALL, wild, ctx) shouldBe 127
        wild.status = StatusCondition.SLEEP_MASK and 2
        CatchCalculator.odds(Items.POKE_BALL, wild, ctx) shouldBe 170
        wild.status = StatusCondition.PARALYSIS
        CatchCalculator.odds(Items.POKE_BALL, wild, ctx) shouldBe 127
        val goldeen = stateOf(testMon(118, 10, listOf(TACKLE)), null)
        CatchCalculator.multiplier(Items.NET_BALL, goldeen, ctx) shouldBe 30
        CatchCalculator.multiplier(Items.NEST_BALL, goldeen, ctx) shouldBe 30
        CatchCalculator.multiplier(Items.TIMER_BALL, goldeen, ctx.copy(turn = 35)) shouldBe 40
        CatchCalculator.multiplier(Items.DIVE_BALL, goldeen, ctx.copy(underwater = true)) shouldBe
            35
        CatchCalculator.multiplier(
            Items.REPEAT_BALL, goldeen, ctx.copy(alreadyCaught = true)) shouldBe 30
        CatchCalculator.roll(
                Items.MASTER_BALL,
                stateOf(testMon(150, 70, listOf(TACKLE)), null),
                ctx,
                BattleRng(1))
            .caught
            .shouldBeTrue()
      }

      test("a hard catch shakes 0-3 times and mostly breaks free") {
        val ctx = CatchContext(turn = 1, underwater = false, alreadyCaught = false)
        val mewtwo = stateOf(testMon(150, 70, listOf(TACKLE)), null) // catch rate 3
        val rng = BattleRng(7)
        val results = List(300) { CatchCalculator.roll(Items.POKE_BALL, mewtwo, ctx, rng) }
        results.count { it.caught } shouldBeLessThan 10
        results.map { it.shakes }.toSet().size shouldBeGreaterThan 1
        results.all { it.shakes in 0..3 }.shouldBeTrue()
      }

      // --- bag in battle ------------------------------------------------------------------------

      test("bag items heal, revive and cure") {
        val me = stateOf(testMon(1, 30, listOf(SPLASH)))
        val foe = stateOf(testMon(19, 30, listOf(SPLASH)), null)
        val b = single(me, foe)
        me.currentHp = 5
        engine.useBagItem(b, "Potion", BattleItemEffect.Heal(20), me).first.shouldBeTrue()
        me.currentHp shouldBe 25
        me.currentHp = 0
        engine.useBagItem(b, "Potion", BattleItemEffect.Heal(20), me).first.shouldBeFalse()
        engine.useBagItem(b, "Revive", BattleItemEffect.Revive(0.5), me).first.shouldBeTrue()
        me.currentHp shouldBe me.maxHp / 2
        me.status = StatusCondition.BURN
        engine
            .useBagItem(b, "Antidote", BattleItemEffect.Cure(StatusCondition.POISON), me)
            .first
            .shouldBeFalse()
        engine
            .useBagItem(b, "Burn Heal", BattleItemEffect.Cure(StatusCondition.BURN), me)
            .first
            .shouldBeTrue()
        me.hasStatus.shouldBeFalse()
        engine
            .useBagItem(b, "X Attack", BattleItemEffect.StatUp(BattleStat.ATTACK), me)
            .first
            .shouldBeTrue()
        me.stage(BattleStat.ATTACK) shouldBe 1
      }

      // --- double battles ------------------------------------------------------------------------

      test("a both-foes move hits both foes in a double battle at reduced damage") {
        val user = stateOf(testMon(7, 50, listOf(SURF)), 0)
        val ally = stateOf(testMon(1, 50, listOf(SPLASH)), 1)
        val f1 = stateOf(testMon(74, 50, listOf(SPLASH)), null)
        val f2 = stateOf(testMon(74, 50, listOf(SPLASH)), null)
        val b = doubles(listOf(user, ally), listOf(f1, f2))
        engine.resolve(
            b,
            listOf(
                BattleChoice.UseMove(0, 0, SURF),
                BattleChoice.UseMove(0, 1, SPLASH),
                BattleChoice.UseMove(1, 0, SPLASH),
                BattleChoice.UseMove(1, 1, SPLASH)))
        (f1.currentHp < f1.maxHp).shouldBeTrue()
        (f2.currentHp < f2.maxHp).shouldBeTrue()
        ally.currentHp shouldBe ally.maxHp
        // Spread damage is halved against the same single-target hit.
        val r = MoveRunner(engine.context(b))
        val surf = moveReg.get(SURF)!!
        val one = r.damage(user, f1, surf, surf.power, surf.type, false, false)
        val spread =
            MoveRunner(engine.context(b)).damage(user, f1, surf, surf.power, surf.type, false, true)
        spread.damage shouldBeLessThan one.damage
      }

      test("a single-target move in a double battle hits the chosen foe") {
        val user = stateOf(testMon(4, 50, listOf(FLAMETHROWER)), 0)
        val ally = stateOf(testMon(1, 50, listOf(SPLASH)), 1)
        val f1 = stateOf(testMon(19, 50, listOf(SPLASH)), null)
        val f2 = stateOf(testMon(19, 50, listOf(SPLASH)), null)
        val b = doubles(listOf(user, ally), listOf(f1, f2), seed = 11)
        engine.resolve(
            b,
            listOf(
                BattleChoice.UseMove(0, 0, FLAMETHROWER, BattlerRef(1, 1)),
                BattleChoice.UseMove(0, 1, SPLASH),
                BattleChoice.UseMove(1, 0, SPLASH),
                BattleChoice.UseMove(1, 1, SPLASH)))
        f1.currentHp shouldBe f1.maxHp
        f2.currentHp shouldNotBe f2.maxHp
      }

      test("Protect blocks a move aimed at the protected monster") {
        val user = stateOf(testMon(4, 50, listOf(FLAMETHROWER)), 0)
        val protector = stateOf(testMon(1, 50, listOf(PROTECT)), null)
        val events =
            engine.resolve(
                single(user, protector, seed = 3),
                listOf(
                    BattleChoice.UseMove(0, 0, FLAMETHROWER), BattleChoice.UseMove(1, 0, PROTECT)))
        protector.currentHp shouldBe protector.maxHp
        events
            .filterIsInstance<BattleEvent.Message>()
            .any { it.text.endsWith("protected itself!") }
            .shouldBeTrue()
      }

      // --- weather
      // ---------------------------------------------------------------------------------

      test("Rain Dance sets five turns of rain that boost water and weaken fire") {
        val me = stateOf(testMon(7, 30, listOf(RAIN_DANCE)))
        val foe = stateOf(testMon(19, 30, listOf(SPLASH)), null)
        val b = single(me, foe)
        turn(b, RAIN_DANCE)
        b.field.weather shouldBe Weather.RAIN
        repeat(4) { turn(b, SPLASH) }
        b.field.weather shouldBe Weather.NONE
      }

      test("the escape roll always works for a faster monster") {
        val fast = stateOf(testMon(19, 50, listOf(TACKLE)))
        val slow = stateOf(testMon(74, 5, listOf(TACKLE)), null)
        engine.tryEscape(single(fast, slow)).first.shouldBeTrue()
      }

      test("quick attack goes before a faster monster's tackle") {
        val slow = stateOf(testMon(74, 30, listOf(QUICK_ATTACK)))
        val fast = stateOf(testMon(19, 30, listOf(TACKLE)), null)
        val ev = turn(single(slow, fast), QUICK_ATTACK, TACKLE)
        ev.filterIsInstance<BattleEvent.MoveUsed>().first().attackerId shouldBe slow.entityId
      }

      test("the battle status is written back by persisted()") {
        val m = stateOf(testMon(1, 30, listOf(SPLASH)))
        m.status = StatusCondition.POISON
        m.persisted().status shouldBe StatusCondition.POISON
        m.currentHp = 0
        m.persisted().status shouldBe 0
      }
    })
