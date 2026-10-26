package de.fiereu.openmmo.web

import de.fiereu.openmmo.client.gfx.GbaGame
import de.fiereu.openmmo.client.gfx.PretData
import de.fiereu.openmmo.client.net.BattleNames
import de.fiereu.openmmo.client.world.MapKey
import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** Everything the state mapping needs to turn ids into text. Swappable in tests. */
interface Names {
  fun species(dex: Int): String

  fun move(id: Int): String

  fun maxPp(id: Int): Int

  fun item(id: Int): String

  fun mapName(key: MapKey): String?

  /** Party records carry no max hp; it is the gen 3 formula over the species' base hp. */
  fun maxHp(mon: Pokemon): Int
}

/**
 * The tables the desktop client names things with: the codegen species/move/item registries, the
 * decomp's `item_names.json` for wire item ids the registry does not know, and the pret map headers
 * for map names.
 */
class CodegenNames(private val decomp: File) : Names {
  private val speciesRegistry by lazy { SpeciesRegistry() }
  private val itemRegistry by lazy { ItemRegistry() }
  private val itemNames: List<String> by lazy {
    val f = File(decomp, "pokeblack/item_names.json")
    if (!f.isFile) emptyList()
    else Json.parseToJsonElement(f.readText()).jsonArray.map { it.jsonPrimitive.content }
  }
  private val pret = ConcurrentHashMap<GbaGame, PretData>()
  private val mapNames = ConcurrentHashMap<MapKey, String>()

  override fun species(dex: Int): String = BattleNames.species(dex)

  override fun move(id: Int): String = BattleNames.move(id)

  override fun maxPp(id: Int): Int = BattleNames.maxPp(id)

  override fun item(id: Int): String =
      itemRegistry.get(id)?.name
          ?: (if (id >= ITEM_REGION_BLOCK) itemNames.getOrNull(id - ITEM_REGION_BLOCK) else null)
          ?: "#$id"

  override fun mapName(key: MapKey): String? {
    mapNames[key]?.let {
      return it.ifEmpty { null }
    }
    val name =
        runCatching {
              val game = GbaGame.forRegion(key.regionId) ?: return@runCatching null
              val data = pret.computeIfAbsent(game) { PretData(decomp, it) }
              synchronized(data) { data.mapName(key.bankId, key.mapId) }
            }
            .getOrNull()
    mapNames[key] = name ?: ""
    return name
  }

  override fun maxHp(mon: Pokemon): Int {
    val base = speciesRegistry.get(mon.dexId)?.baseHp ?: return mon.hp.toInt()
    val level = mon.level.toInt()
    if (base == 1) return 1 // Shedinja
    return (2 * base + mon.iVs.hp + mon.eVs.hp / 4) * level / 100 + level + 10
  }

  private companion object {
    const val ITEM_REGION_BLOCK = 5000
  }
}
