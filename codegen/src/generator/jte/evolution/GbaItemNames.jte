@import java.util.List

@param List<List<String>> rows
package de.fiereu.openmmo.pokemon.generated

/** Generated from the decomp's include/constants/items.h: GBA item number to its constant name. */
object GeneratedGbaItemNames {
  val names: Map<Int, String> =
      mapOf(
@for(List<String> r : rows)
          ${r.get(0)} to "${r.get(1)}",
@endfor
      )
}
