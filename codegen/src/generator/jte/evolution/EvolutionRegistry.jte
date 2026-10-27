@import java.util.List
@import de.fiereu.openmmo.codegen.evolution.ParsedEvolution

@param List<ParsedEvolution> rows
package de.fiereu.openmmo.pokemon.generated

import de.fiereu.openmmo.pokemon.EvolutionDef
import de.fiereu.openmmo.pokemon.EvolutionMethod
import de.fiereu.openmmo.pokemon.EvolutionRegistry

/** Generated from the decomp's gEvolutionTable, do not edit by hand. */
internal object GeneratedEvolutions {
  fun loadInto(reg: EvolutionRegistry) {
@for(ParsedEvolution r : rows)
    reg.register(EvolutionDef(${r.getFromDex()}, EvolutionMethod.${r.getMethod()}, ${r.getParam()}, "${r.getItem()}", ${r.getToDex()}))
@endfor
  }
}
