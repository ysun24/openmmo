package de.fiereu.openmmo.server.game.progression

import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.PokemonMove
import de.fiereu.openmmo.common.enums.EVs
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import de.fiereu.openmmo.server.game.battle.ExpCurves
import de.fiereu.openmmo.server.game.battle.LearnedMove
import de.fiereu.openmmo.server.game.battle.MoveLearner
import de.fiereu.openmmo.server.game.battle.StatCalculator
import de.fiereu.openmmo.server.game.services.EvStat
import javax.inject.Inject
import javax.inject.Singleton

/** A monster after growing, what it learned and what it still wants to learn. */
data class Growth(
    val mon: Pokemon,
    val learned: List<LearnedMove> = emptyList(),
    /** Moves that need the player to forget one first. */
    val offered: List<LearnedMove> = emptyList(),
    /** A second monster to add to the party alongside [mon], if any. */
    val extra: Pokemon? = null,
)

/**
 * Level-up outside battle, following the decomp: Rare Candy (`ItemUseCB_RareCandy` then
 * `MonTryLearningNewMove`) and `CalculateMonStats` keeping the damage a monster had taken.
 * Evolution itself is [de.fiereu.openmmo.server.game.services.EvolutionService], the one evolution
 * engine (level, stone, friendship, trade).
 */
@Singleton
class MonGrowth
@Inject
constructor(
    private val species: SpeciesRegistry,
    private val learner: MoveLearner,
) {

  fun maxHp(mon: Pokemon): Int =
      species.get(mon.dexId)?.let { StatCalculator.computeAll(it, mon).hp } ?: mon.hp.toInt()

  /** One level up, as a Rare Candy gives it: exp to the new level's floor and the moves. */
  fun levelUp(mon: Pokemon): Growth? {
    val def = species.get(mon.dexId) ?: return null
    val level = mon.level.toInt()
    if (level >= ExpCurves.MAX_LEVEL) return null
    val raised =
        rescaleHp(
            mon,
            mon.copy(
                level = (level + 1).toByte(), xp = ExpCurves.totalXpFor(def.growthRate, level + 1)))
    val known = raised.moves.toMutableList()
    val outcome = learner.learn(known, raised.dexId, level, level + 1)
    return Growth(raised.copy(moves = known), outcome.learned, outcome.offered)
  }

  /** CalculateMonStats: the new max hp keeps the hp the monster was missing. */
  fun rescaleHp(before: Pokemon, after: Pokemon): Pokemon {
    val oldMax = maxHp(before)
    val newMax = maxHp(after)
    val hp = (before.hp + (newMax - oldMax)).coerceIn(0, newMax)
    return after.copy(hp = hp.toShort())
  }

  /**
   * A copy of [evs] with [stat] set to [value] to one stat (EnumMap underneath, so never in place).
   */
  fun withEv(evs: EVs, stat: EvStat, value: Int): EVs =
      EVs().also {
        it.hp = evs.hp
        it.atk = evs.atk
        it.def = evs.def
        it.spAtk = evs.spAtk
        it.spDef = evs.spDef
        it.spd = evs.spd
        when (stat) {
          EvStat.HP -> it.hp = value
          EvStat.ATK -> it.atk = value
          EvStat.DEF -> it.def = value
          EvStat.SPEED -> it.spd = value
          EvStat.SPATK -> it.spAtk = value
          EvStat.SPDEF -> it.spDef = value
        }
      }

  /** Replaces the move in [slot] (or the first free one when [slot] is -1) with [moveId]. */
  fun teach(mon: Pokemon, moveId: Int, pp: Int, slot: Int): Pokemon? {
    val moves = (mon.moves + List(4 - mon.moves.size) { PokemonMove(0, 0) }).toMutableList()
    val target = if (slot < 0) moves.indexOfFirst { it.id.toInt() == 0 } else slot
    if (target !in 0..3) return null
    moves[target] = PokemonMove(moveId.toShort(), pp.toByte())
    // A taught move starts without the old slot's PP Ups.
    val bonuses = mon.ppBonuses and (3 shl (target * 2)).inv()
    return mon.copy(moves = moves, ppBonuses = bonuses)
  }
}
