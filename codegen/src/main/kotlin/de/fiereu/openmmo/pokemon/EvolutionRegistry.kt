package de.fiereu.openmmo.pokemon

import de.fiereu.openmmo.pokemon.generated.GeneratedEvolutions
import javax.inject.Inject
import javax.inject.Singleton

/** The decomp's `EVO_` methods (include/constants/pokemon.h). */
enum class EvolutionMethod {
  FRIENDSHIP,
  FRIENDSHIP_DAY,
  FRIENDSHIP_NIGHT,
  LEVEL,
  TRADE,
  TRADE_ITEM,
  ITEM,
  LEVEL_ATK_GT_DEF,
  LEVEL_ATK_EQ_DEF,
  LEVEL_ATK_LT_DEF,
  LEVEL_SILCOON,
  LEVEL_CASCOON,
  LEVEL_NINJASK,
  LEVEL_SHEDINJA,
  BEAUTY,
}

/**
 * One evolution of [fromDex] into [toDex]. [param] is the level / friendship / beauty threshold,
 * [item] the decomp item constant without `ITEM_` for stone and trade-item evolutions.
 */
data class EvolutionDef(
    val fromDex: Int,
    val method: EvolutionMethod,
    val param: Int,
    val item: String,
    val toDex: Int,
)

/** Every species' evolutions, in the decomp's table order (which decides ties, as in Gen 3). */
@Singleton
class EvolutionRegistry @Inject constructor() {
  private val byFrom = mutableMapOf<Int, MutableList<EvolutionDef>>()

  init {
    GeneratedEvolutions.loadInto(this)
  }

  fun register(def: EvolutionDef) {
    byFrom.getOrPut(def.fromDex) { mutableListOf() } += def
  }

  fun of(dexId: Int): List<EvolutionDef> = byFrom[dexId].orEmpty()

  fun size(): Int = byFrom.values.sumOf { it.size }
}
