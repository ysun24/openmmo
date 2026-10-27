package de.fiereu.openmmo.server.game.battle

import de.fiereu.openmmo.common.StatusCondition
import de.fiereu.openmmo.common.enums.PokemonType
import de.fiereu.openmmo.items.ItemDef
import de.fiereu.openmmo.items.generated.Items

/** One throw: [shakes] 0-3 wobbles, [caught] when the ball holds (it shakes 3 times first). */
data class CatchResult(val shakes: Int, val caught: Boolean)

/** Facts about the throw the ball multipliers look at. */
data class CatchContext(
    val turn: Int,
    val underwater: Boolean,
    val alreadyCaught: Boolean,
)

/** The Gen 3 catch formula, src/battle_script_commands.c Cmd_handleballthrow. */
object CatchCalculator {

  /** The Gen 3 balls (the decomp's ITEM_MASTER_BALL..ITEM_PREMIER_BALL). */
  val BALLS: Set<ItemDef> =
      setOf(
          Items.MASTER_BALL,
          Items.ULTRA_BALL,
          Items.GREAT_BALL,
          Items.POKE_BALL,
          Items.SAFARI_BALL,
          Items.NET_BALL,
          Items.DIVE_BALL,
          Items.NEST_BALL,
          Items.REPEAT_BALL,
          Items.TIMER_BALL,
          Items.LUXURY_BALL,
          Items.PREMIER_BALL)

  /** The ball's multiplier, per 10 (so 15 is the Great Ball's 1.5). */
  fun multiplier(ball: ItemDef, target: BattleMonState, ctx: CatchContext): Int =
      when (ball) {
        Items.ULTRA_BALL -> 20
        Items.GREAT_BALL,
        Items.SAFARI_BALL -> 15
        Items.NET_BALL ->
            if (target.hasType(PokemonType.WATER) || target.hasType(PokemonType.BUG)) 30 else 10
        Items.DIVE_BALL -> if (ctx.underwater) 35 else 10
        Items.NEST_BALL ->
            if (target.level < 40) (40 - target.level).let { if (it <= 9) 10 else it } else 10
        Items.REPEAT_BALL -> if (ctx.alreadyCaught) 30 else 10
        Items.TIMER_BALL -> (ctx.turn + 10).coerceAtMost(40)
        else -> 10
      }

  /** The odds number before the shake test; above 254 is a sure catch. */
  fun odds(ball: ItemDef, target: BattleMonState, ctx: CatchContext): Int {
    val catchRate = target.species.catchRate
    val mult = multiplier(ball, target, ctx)
    val maxHp = target.maxHp.coerceAtLeast(1)
    var odds = (catchRate * mult / 10) * (maxHp * 3 - target.currentHp * 2) / (3 * maxHp)
    val s = target.status
    if (s and (StatusCondition.SLEEP_MASK or StatusCondition.FREEZE) != 0) odds *= 2
    if (s and
        (StatusCondition.POISON or
            StatusCondition.BURN or
            StatusCondition.PARALYSIS or
            StatusCondition.TOXIC) != 0)
        odds = odds * 15 / 10
    return odds
  }

  fun roll(ball: ItemDef, target: BattleMonState, ctx: CatchContext, rng: BattleRng): CatchResult {
    if (ball == Items.MASTER_BALL) return CatchResult(3, true)
    val odds = odds(ball, target, ctx)
    if (odds > 254) return CatchResult(3, true)
    if (odds <= 0) return CatchResult(0, false)
    // odds = 1048560 / sqrt(sqrt(16711680 / odds)), then up to four 16-bit rolls below it.
    val shakeOdds = 1048560 / isqrt(isqrt(16711680 / odds)).coerceAtLeast(1)
    var shakes = 0
    while (shakes < 4 && rng.random16() < shakeOdds) shakes++
    return if (shakes == 4) CatchResult(3, true) else CatchResult(shakes, false)
  }

  /** The GBA's integer square root (Sqrt in src/math_util.c), floor. */
  internal fun isqrt(n: Int): Int {
    if (n <= 0) return 0
    var x = Math.sqrt(n.toDouble()).toInt()
    while (x * x > n) x--
    while ((x + 1) * (x + 1) <= n) x++
    return x
  }
}

/** What a bag item does in battle, from the decomp's item_effects.h and battle use functions. */
sealed interface BattleItemEffect {
  /** Heals [hp] (Int.MAX_VALUE fills); [cures] also clears status and confusion (Full Restore). */
  data class Heal(val hp: Int, val cures: Boolean = false) : BattleItemEffect

  data class Revive(val fraction: Double) : BattleItemEffect

  /** Cures the listed STATUS1 bits; [confusion] also confusion. */
  data class Cure(val bits: Int, val confusion: Boolean = false) : BattleItemEffect

  /** Restores pp: [allMoves] or the one picked move; Int.MAX_VALUE fills. */
  data class RestorePp(val amount: Int, val allMoves: Boolean) : BattleItemEffect

  data class StatUp(val stat: BattleStat) : BattleItemEffect

  data object DireHit : BattleItemEffect

  data object GuardSpec : BattleItemEffect

  /** Poké Doll / Fluffy Tail: leaves any wild battle. */
  data object Escape : BattleItemEffect

  data object Ball : BattleItemEffect
}

object BattleItemEffects {
  private const val FULL = Int.MAX_VALUE
  private const val ALL_STATUS = 0xFF

  private val table: Map<ItemDef, BattleItemEffect> = buildMap {
    CatchCalculator.BALLS.forEach { put(it, BattleItemEffect.Ball) }
    put(Items.POTION, BattleItemEffect.Heal(20))
    put(Items.SUPER_POTION, BattleItemEffect.Heal(50))
    put(Items.HYPER_POTION, BattleItemEffect.Heal(200))
    put(Items.MAX_POTION, BattleItemEffect.Heal(FULL))
    put(Items.FULL_RESTORE, BattleItemEffect.Heal(FULL, cures = true))
    put(Items.FRESH_WATER, BattleItemEffect.Heal(50))
    put(Items.SODA_POP, BattleItemEffect.Heal(60))
    put(Items.LEMONADE, BattleItemEffect.Heal(80))
    put(Items.MOOMOO_MILK, BattleItemEffect.Heal(100))
    put(Items.ENERGYPOWDER, BattleItemEffect.Heal(50))
    put(Items.ENERGY_ROOT, BattleItemEffect.Heal(200))
    put(Items.BERRY_JUICE, BattleItemEffect.Heal(20))
    put(Items.ORAN_BERRY, BattleItemEffect.Heal(10))
    put(Items.SITRUS_BERRY, BattleItemEffect.Heal(30))
    put(Items.REVIVE, BattleItemEffect.Revive(0.5))
    put(Items.MAX_REVIVE, BattleItemEffect.Revive(1.0))
    put(Items.REVIVAL_HERB, BattleItemEffect.Revive(1.0))
    put(Items.ANTIDOTE, BattleItemEffect.Cure(StatusCondition.POISON or StatusCondition.TOXIC))
    put(Items.PECHA_BERRY, BattleItemEffect.Cure(StatusCondition.POISON or StatusCondition.TOXIC))
    put(Items.BURN_HEAL, BattleItemEffect.Cure(StatusCondition.BURN))
    put(Items.RAWST_BERRY, BattleItemEffect.Cure(StatusCondition.BURN))
    put(Items.ICE_HEAL, BattleItemEffect.Cure(StatusCondition.FREEZE))
    put(Items.ASPEAR_BERRY, BattleItemEffect.Cure(StatusCondition.FREEZE))
    put(Items.AWAKENING, BattleItemEffect.Cure(StatusCondition.SLEEP_MASK))
    put(Items.CHESTO_BERRY, BattleItemEffect.Cure(StatusCondition.SLEEP_MASK))
    put(Items.BLUE_FLUTE, BattleItemEffect.Cure(StatusCondition.SLEEP_MASK))
    put(Items.PARLYZ_HEAL, BattleItemEffect.Cure(StatusCondition.PARALYSIS))
    put(Items.CHERI_BERRY, BattleItemEffect.Cure(StatusCondition.PARALYSIS))
    put(Items.FULL_HEAL, BattleItemEffect.Cure(ALL_STATUS, confusion = true))
    put(Items.HEAL_POWDER, BattleItemEffect.Cure(ALL_STATUS, confusion = true))
    put(Items.LAVA_COOKIE, BattleItemEffect.Cure(ALL_STATUS, confusion = true))
    put(Items.LUM_BERRY, BattleItemEffect.Cure(ALL_STATUS, confusion = true))
    put(Items.PERSIM_BERRY, BattleItemEffect.Cure(0, confusion = true))
    put(Items.YELLOW_FLUTE, BattleItemEffect.Cure(0, confusion = true))
    put(Items.ETHER, BattleItemEffect.RestorePp(10, allMoves = false))
    put(Items.LEPPA_BERRY, BattleItemEffect.RestorePp(10, allMoves = false))
    put(Items.MAX_ETHER, BattleItemEffect.RestorePp(FULL, allMoves = false))
    put(Items.ELIXIR, BattleItemEffect.RestorePp(10, allMoves = true))
    put(Items.MAX_ELIXIR, BattleItemEffect.RestorePp(FULL, allMoves = true))
    put(Items.X_ATTACK, BattleItemEffect.StatUp(BattleStat.ATTACK))
    put(Items.X_DEFEND, BattleItemEffect.StatUp(BattleStat.DEFENSE))
    put(Items.X_SPEED, BattleItemEffect.StatUp(BattleStat.SPEED))
    put(Items.X_SPECIAL, BattleItemEffect.StatUp(BattleStat.SP_ATTACK))
    put(Items.X_ACCURACY, BattleItemEffect.StatUp(BattleStat.ACCURACY))
    put(Items.DIRE_HIT, BattleItemEffect.DireHit)
    put(Items.GUARD_SPEC, BattleItemEffect.GuardSpec)
    put(Items.POKE_DOLL, BattleItemEffect.Escape)
    put(Items.FLUFFY_TAIL, BattleItemEffect.Escape)
  }

  fun of(item: ItemDef): BattleItemEffect? = table[item]

  fun all(): Map<ItemDef, BattleItemEffect> = table

  /** Which bag target an item needs: "foe" (balls), "party", "active" or "none". */
  fun target(effect: BattleItemEffect): String =
      when (effect) {
        BattleItemEffect.Ball -> "foe"
        is BattleItemEffect.Heal,
        is BattleItemEffect.Revive,
        is BattleItemEffect.Cure,
        is BattleItemEffect.RestorePp -> "party"
        is BattleItemEffect.StatUp,
        BattleItemEffect.DireHit,
        BattleItemEffect.GuardSpec -> "active"
        BattleItemEffect.Escape -> "none"
      }
}
