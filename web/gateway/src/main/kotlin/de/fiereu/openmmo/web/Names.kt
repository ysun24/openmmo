package de.fiereu.openmmo.web

import de.fiereu.openmmo.client.gfx.GbaGame
import de.fiereu.openmmo.client.gfx.PretData
import de.fiereu.openmmo.client.net.BattleNames
import de.fiereu.openmmo.client.world.MapKey
import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.enums.Ability
import de.fiereu.openmmo.common.enums.PokemonStat
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import de.fiereu.openmmo.progression.Gen3Data
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

  /** Pocket, description, price and field use of an item, when known. */
  fun itemInfo(id: Int): ItemInfo? = null

  /** Who speaks through an npc with graphics [gfx] in [region]: named characters only. */
  fun speaker(region: Int, gfx: Int): String? = null

  /** Stats, nature, ability and types for the party summary. */
  fun summary(mon: Pokemon): MonSummary? = null

  /** Whether the map has the PC: any map with a Pokemon Center nurse, as the server decides. */
  fun hasPc(key: MapKey): Boolean = false

  /** The pokedex page of national [dex], worded as [region]'s game does (0 Kanto, 1 Hoenn). */
  fun dexEntry(dex: Int, region: Int): DexEntry? = null

  /** The species' types, for the pokedex list. */
  fun types(dex: Int): List<String> = emptyList()

  /** A move's description (the TM case and the learn prompt). */
  fun moveDescription(id: Int): String? = null
}

data class MonSummary(
    val stats: Map<String, Int>,
    val nature: String,
    val ability: String,
    val types: List<String>,
)

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
  private val items by lazy { ItemCatalog(decomp) }
  private val maps by lazy { de.fiereu.openmmo.maps.MapManager() }
  private val gfxNames = ConcurrentHashMap<Int, Map<Int, String>>()
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

  private val gen3Text by lazy { Gen3Text(decomp) }

  override fun itemInfo(id: Int): ItemInfo? {
    val info = items.info(id) ?: return null
    // A gen 3 TM teaches the Emerald/FireRed move of its number, not gen 5's: name it from the
    // decomp's tms_hms.h order (Gen3Data) instead of leaving the description blank.
    val def = itemRegistry.get(id) ?: return info
    val index = Gen3Data.tmhmIndex(def) ?: return info.copy(use = info.use.takeUnless { info.pocket == "tms" })
    val moveId = Gen3Data.tmhmMove(index) ?: return info
    val text = gen3Text.moveDescription(moveId).orEmpty()
    return info.copy(
        description = "Teaches ${move(moveId)}. $text".trim(),
        use = "teach",
        teaches = moveId,
        tmIndex = index)
  }

  override fun dexEntry(dex: Int, region: Int): DexEntry? =
      gen3Text.dexEntry(dex, if (region == 0) Gen3Text.FIRERED else Gen3Text.EMERALD)

  override fun types(dex: Int): List<String> =
      speciesRegistry.get(dex)?.types?.map { it.name }?.distinct().orEmpty()

  override fun moveDescription(id: Int): String? = gen3Text.moveDescription(id)

  override fun hasPc(key: MapKey): Boolean =
      maps.getMap(key.regionId, key.bankId, key.mapId)?.npcs?.any {
        it.script.endsWith("_EventScript_Nurse")
      } ?: false

  override fun speaker(region: Int, gfx: Int): String? {
    val names =
        gfxNames.computeIfAbsent(region) {
          val game = if (region == 0) "pokefirered" else "pokeemerald"
          val f = File(decomp, "$game/include/constants/event_objects.h")
          if (!f.isFile) return@computeIfAbsent emptyMap()
          Regex("""#define OBJ_EVENT_GFX_(\w+)\s+(\d+)""").findAll(f.readText()).associate {
            it.groupValues[2].toInt() to it.groupValues[1]
          }
        }
    val token = names[gfx] ?: return null
    SPEAKERS[token]?.let {
      return it
    }
    // Rival sprites carry a suffix per pose (RIVAL_MAY_NORMAL, RIVAL_BRENDAN_MACH_BIKE...).
    return SPEAKERS.entries.firstOrNull { token.startsWith(it.key + "_") }?.value
  }

  override fun summary(mon: Pokemon): MonSummary? {
    val sp = speciesRegistry.get(mon.dexId) ?: return null
    val level = mon.level.toInt()
    val nature = mon.nature
    fun stat(base: Int, iv: Int, ev: Int, which: PokemonStat): Int {
      val raw = (2 * base + iv + ev / 4) * level / 100 + 5
      return when {
        nature.increasedStat == nature.decreasedStat -> raw
        nature.increasedStat == which -> raw * 110 / 100
        nature.decreasedStat == which -> raw * 90 / 100
        else -> raw
      }
    }
    val iv = mon.iVs
    val ev = mon.eVs
    // Gen 3: the personality's lowest bit picks the second ability, when the species has one.
    val ability =
        if ((mon.seed and 1) == 1 && sp.ability2 != Ability.NONE) sp.ability2 else sp.ability1
    return MonSummary(
        stats =
            linkedMapOf(
                "hp" to maxHp(mon),
                "atk" to stat(sp.baseAttack, iv.atk, ev.atk, PokemonStat.ATTACK),
                "def" to stat(sp.baseDefense, iv.def, ev.def, PokemonStat.DEFENSE),
                "spa" to stat(sp.baseSpAttack, iv.spAtk, ev.spAtk, PokemonStat.SP_ATTACK),
                "spd" to stat(sp.baseSpDefense, iv.spDef, ev.spDef, PokemonStat.SP_DEFENSE),
                "spe" to stat(sp.baseSpeed, iv.spd, ev.spd, PokemonStat.SPEED),
            ),
        nature = nature.name,
        ability = ability.name.replace('_', ' '),
        types = sp.types.map { it.name }.distinct(),
    )
  }

  private companion object {
    const val ITEM_REGION_BLOCK = 5000

    /** OBJ_EVENT_GFX_* tokens of characters with a name, across both games. */
    val SPEAKERS =
        mapOf(
            "PROF_OAK" to "PROF. OAK",
            "PROF_BIRCH" to "PROF. BIRCH",
            "MOM" to "MOM",
            "NURSE" to "NURSE",
            "CLERK" to "CLERK",
            "MART_EMPLOYEE" to "CLERK",
            "BLUE" to "GREEN",
            "BILL" to "BILL",
            "RIVAL_MAY" to "MAY",
            "RIVAL_BRENDAN" to "BRENDAN",
            "BROCK" to "BROCK",
            "MISTY" to "MISTY",
            "LT_SURGE" to "LT. SURGE",
            "ERIKA" to "ERIKA",
            "KOGA" to "KOGA",
            "SABRINA" to "SABRINA",
            "BLAINE" to "BLAINE",
            "GIOVANNI" to "GIOVANNI",
            "LORELEI" to "LORELEI",
            "BRUNO" to "BRUNO",
            "AGATHA" to "AGATHA",
            "LANCE" to "LANCE",
            "NORMAN" to "NORMAN",
            "ROXANNE" to "ROXANNE",
            "BRAWLY" to "BRAWLY",
            "WATTSON" to "WATTSON",
            "FLANNERY" to "FLANNERY",
            "WINONA" to "WINONA",
            "TATE" to "TATE",
            "LIZA" to "LIZA",
            "JUAN" to "JUAN",
            "WALLACE" to "WALLACE",
            "STEVEN" to "STEVEN",
            "WALLY" to "WALLY",
            "SIDNEY" to "SIDNEY",
            "PHOEBE" to "PHOEBE",
            "GLACIA" to "GLACIA",
            "DRAKE" to "DRAKE",
            "MR_BRINEY" to "MR. BRINEY",
            "ARCHIE" to "ARCHIE",
            "MAXIE" to "MAXIE",
        )
  }
}
