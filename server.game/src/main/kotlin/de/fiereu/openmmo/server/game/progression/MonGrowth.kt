package de.fiereu.openmmo.server.game.progression

import de.fiereu.openmmo.common.MAX_PARTY_SIZE
import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.PokemonMove
import de.fiereu.openmmo.common.enums.EVs
import de.fiereu.openmmo.items.ItemDef
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import de.fiereu.openmmo.server.game.battle.ExpCurves
import de.fiereu.openmmo.server.game.battle.LearnedMove
import de.fiereu.openmmo.server.game.battle.MoveLearner
import de.fiereu.openmmo.server.game.battle.StatCalculator
import de.fiereu.openmmo.server.game.services.EvStat
import javax.inject.Inject
import javax.inject.Singleton

/** A monster after growing, what it learned, what it still wants to learn, and what it became. */
data class Growth(
    val mon: Pokemon,
    val learned: List<LearnedMove> = emptyList(),
    /** Moves that need the player to forget one first. */
    val offered: List<LearnedMove> = emptyList(),
    /** The species it evolved from, when it evolved. */
    val evolvedFrom: Int? = null,
    /** A second monster the evolution produced (Nincada's Shedinja). */
    val extra: Pokemon? = null,
)

/**
 * Level-up and evolution outside battle, following the decomp: Rare Candy (`ItemUseCB_RareCandy`
 * then `MonTryLearningNewMove`), `GetEvolutionTargetSpecies` for the level and item modes, and
 * `CalculateMonStats` keeping the damage a monster had taken. The battle branch owns the in-battle
 * evolution scene; this is the field side and can be reused there.
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
                level = (level + 1).toByte(),
                xp = ExpCurves.totalXpFor(def.growthRate, level + 1)))
    val known = raised.moves.toMutableList()
    val outcome = learner.learn(known, raised.dexId, level, level + 1)
    return Growth(raised.copy(moves = known), outcome.learned, outcome.offered)
  }

  /** The species [mon] becomes by levelling now ([partySize] for Shedinja), or null. */
  fun levelEvolution(mon: Pokemon): Evolution? {
    if (mon.isEgg) return null
    val level = mon.level.toInt()
    val stats = species.get(mon.dexId)?.let { StatCalculator.computeAll(it, mon) } ?: return null
    val personality = mon.seed.toLong() and 0xFFFFFFFFL
    return Gen3Data.evolutions[mon.dexId].orEmpty().firstOrNull { e ->
      level >= e.level &&
          when (e.method) {
            "LEVEL" -> true
            "LEVEL_ATK_GT_DEF" -> stats.atk > stats.def
            "LEVEL_ATK_EQ_DEF" -> stats.atk == stats.def
            "LEVEL_ATK_LT_DEF" -> stats.atk < stats.def
            // GetEvolutionTargetSpecies: (personality >> 16) % 10 picks Wurmple's cocoon.
            "LEVEL_SILCOON" -> (personality shr 16) % 10 <= 4
            "LEVEL_CASCOON" -> (personality shr 16) % 10 > 4
            "LEVEL_NINJASK" -> true
            else -> false
          }
    }
  }

  /** The evolution [item] triggers on [mon] (EVO_MODE_ITEM_USE), or null. */
  fun itemEvolution(mon: Pokemon, item: ItemDef): Evolution? {
    if (mon.isEgg) return null
    return Gen3Data.evolutions[mon.dexId].orEmpty().firstOrNull {
      it.method == "ITEM" && it.item === item
    }
  }

  /**
   * [mon] as [evolution]'s target: new stats with the same damage taken, a nickname that was only
   * the species name dropped, and the new species' moves for its level. [partySize] decides
   * whether Nincada also leaves a Shedinja ([newId] gives it an id).
   */
  fun evolve(mon: Pokemon, evolution: Evolution, partySize: Int, newId: () -> Long): Growth {
    val oldName = species.get(mon.dexId)?.name
    val nickname = if (oldName != null && mon.nickname.equals(oldName, true)) "" else mon.nickname
    val evolved = rescaleHp(mon, mon.copy(dexId = evolution.target, nickname = nickname))
    val known = evolved.moves.toMutableList()
    val level = evolved.level.toInt()
    val outcome = learner.learn(known, evolved.dexId, level - 1, level)
    val extra =
        if (evolution.method == "LEVEL_NINJASK" && partySize < MAX_PARTY_SIZE) {
          shedinjaFrom(mon, evolved.copy(moves = known), newId())
        } else null
    return Growth(evolved.copy(moves = known), outcome.learned, outcome.offered, mon.dexId, extra)
  }

  private fun shedinjaFrom(nincada: Pokemon, ninjask: Pokemon, id: Long): Pokemon? {
    val shedinja = Gen3Data.evolutions[nincada.dexId].orEmpty().firstOrNull {
      it.method == "LEVEL_SHEDINJA"
    } ?: return null
    val mon = ninjask.copy(id = id, dexId = shedinja.target, nickname = "", status = 0)
    return mon.copy(hp = maxHp(mon).toShort())
  }

  /** CalculateMonStats: the new max hp keeps the hp the monster was missing. */
  fun rescaleHp(before: Pokemon, after: Pokemon): Pokemon {
    val oldMax = maxHp(before)
    val newMax = maxHp(after)
    val hp = (before.hp + (newMax - oldMax)).coerceIn(0, newMax)
    return after.copy(hp = hp.toShort())
  }

  /** A copy of [evs] with [stat] set to [value] to one stat (EnumMap underneath, so never in place). */
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
