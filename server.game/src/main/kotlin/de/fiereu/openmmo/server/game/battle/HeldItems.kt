package de.fiereu.openmmo.server.game.battle

import de.fiereu.openmmo.common.enums.PokemonType
import de.fiereu.openmmo.items.ItemDef
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.pokemon.generated.GeneratedHoldEffects

/** A held item's battle effect: the decomp's `HOLD_EFFECT_` name and its `holdEffectParam`. */
data class HoldEffect(val name: String, val param: Int, val item: ItemDef)

/**
 * The decomp's hold effect table (src/data/items.h) keyed by our wire item ids. Items the decomp
 * gives no hold effect have none here either.
 */
class HeldItems(private val items: ItemRegistry) {
  private val byDef: Map<ItemDef, Pair<String, Int>> =
      GeneratedHoldEffects.effects.entries
          .mapNotNull { (name, eff) -> ItemNames.byDecompName(name)?.let { it to eff } }
          .toMap()

  fun of(itemId: Int): HoldEffect? {
    if (itemId == 0) return null
    val def = items.get(itemId) ?: return null
    val (name, param) = byDef[def] ?: return null
    return HoldEffect(name, param, def)
  }

  fun name(itemId: Int): String = items.get(itemId)?.name ?: "item"

  companion object {
    /** HOLD_EFFECT_<TYPE>_POWER: the type a held item boosts by its param percent. */
    fun boostedType(effect: HoldEffect): PokemonType? {
      if (!effect.name.endsWith("_POWER")) return null
      val type = effect.name.removeSuffix("_POWER")
      return PokemonType.entries.firstOrNull { it.name == type }
    }
  }
}
