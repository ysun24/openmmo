@import java.util.List

@param List<List<String>> rows
package de.fiereu.openmmo.pokemon.generated

/**
 * Generated from the decomp's src/data/items.h: item constant name (without ITEM_) to its
 * HOLD_EFFECT_ name (without the prefix) and holdEffectParam.
 */
object GeneratedHoldEffects {
  val effects: Map<String, Pair<String, Int>> =
      mapOf(
@for(List<String> r : rows)
          "${r.get(0)}" to ("${r.get(1)}" to ${r.get(2)}),
@endfor
      )
}
