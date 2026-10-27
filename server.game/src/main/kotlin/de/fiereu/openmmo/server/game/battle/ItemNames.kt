package de.fiereu.openmmo.server.game.battle

import de.fiereu.openmmo.items.ItemDef
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.pokemon.generated.GeneratedGbaItemNames

/**
 * Bridges the decomp's `ITEM_` constant names (and GBA item numbers) to our wire item catalogue.
 * The catalogue spells a few names without underscores (THUNDERSTONE, NEVERMELTICE), so names are
 * compared with the underscores dropped.
 */
object ItemNames {
  private val byKey: Map<String, ItemDef> by lazy {
    Items::class
        .java
        .declaredMethods
        .filter { it.name.startsWith("get") && it.parameterCount == 0 }
        .filter { ItemDef::class.java.isAssignableFrom(it.returnType) }
        .associate { m -> key(m.name.removePrefix("get")) to (m.invoke(Items) as ItemDef) }
  }

  private fun key(name: String): String = name.uppercase().replace("_", "")

  /** The catalogue item for a decomp constant name without `ITEM_` (e.g. "LEFTOVERS"). */
  fun byDecompName(name: String): ItemDef? =
      if (name.isBlank() || name == "NONE") null else byKey[key(name)]

  /** The catalogue item for a GBA item number (a species' itemCommon / itemRare). */
  fun byGbaId(id: Int): ItemDef? = GeneratedGbaItemNames.names[id]?.let(::byDecompName)

  /** The wire id of a decomp-named item, or 0 when the catalogue has none. */
  fun wireId(items: ItemRegistry, name: String): Int =
      byDecompName(name)?.let { items.idOrNull(it) } ?: 0
}
