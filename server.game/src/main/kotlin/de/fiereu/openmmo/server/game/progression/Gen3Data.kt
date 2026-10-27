package de.fiereu.openmmo.server.game.progression

import de.fiereu.openmmo.items.ItemDef
import de.fiereu.openmmo.items.generated.Items

/** One way a species evolves, as in the decomp's evolution.h. */
data class Evolution(
    /** The decomp's EVO_* method without the prefix: LEVEL, ITEM, TRADE, FRIENDSHIP, … */
    val method: String,
    /** A level for the LEVEL_* methods, 0 otherwise. */
    val level: Int,
    /** The item for ITEM / TRADE_ITEM, null otherwise. */
    val item: ItemDef?,
    val target: Int,
)

data class Decoration(val id: Int, val name: String, val category: String, val price: Int)

/** The parsed [Gen3Tables]. Everything is keyed by national dex number. */
object Gen3Data {
  /** TM01..TM50 then HM01..HM08. */
  val tmhmMoves: List<Int> = Gen3Tables.TMHM_MOVES.split(',').map { it.toInt() }

  const val TM_COUNT = 50
  const val HM_COUNT = 8

  private val tmhmBits: Map<Int, Long> = pairs(Gen3Tables.TMHM_LEARNSETS) { it.toLong(16) }

  private val eggMoves: Map<Int, List<Int>> =
      pairs(Gen3Tables.EGG_MOVES) { v -> v.split(',').map { it.toInt() } }

  private val itemByConst: Map<String, ItemDef> by lazy {
    Items::class
        .java
        .declaredFields
        .filter { ItemDef::class.java.isAssignableFrom(it.type) }
        .associate {
          it.isAccessible = true
          it.name to (it.get(Items) as ItemDef)
        }
  }

  val evolutions: Map<Int, List<Evolution>> by lazy {
    pairs(Gen3Tables.EVOLUTIONS) { v ->
      v.split('|').map { e ->
        val (method, param, target) = e.split('/')
        if (param.startsWith("I")) {
          Evolution(method, 0, itemByConst[param.substring(1)], target.toInt())
        } else {
          Evolution(method, param.toInt(), null, target.toInt())
        }
      }
    }
  }

  /** The species each one evolves from, the reverse of [evolutions]. */
  val preEvolution: Map<Int, Int> by lazy {
    evolutions.flatMap { (from, evos) -> evos.map { it.target to from } }.toMap()
  }

  val hoennDex: List<Int> = Gen3Tables.HOENN_DEX.split(',').map { it.toInt() }

  /** The Kanto regional dex of FireRed: the first 151 national numbers. */
  val kantoDex: List<Int> = (1..151).toList()

  const val NATIONAL_DEX_SIZE = 386

  val decorations: Map<Int, Decoration> =
      Gen3Tables.DECORATIONS.split(';')
          .map { e ->
            val (id, name, cat, price) = e.split('/')
            Decoration(id.toInt(), name, cat, price.toInt())
          }
          .associateBy { it.id }

  val decorStocks: Map<String, List<Int>> =
      Gen3Tables.DECOR_STOCKS.split(';').associate { e ->
        val (label, ids) = e.split(':')
        label to ids.split(',').map { it.toInt() }
      }

  /** 0-based TM/HM index (TM01 = 0, HM01 = 50) for a TM/HM item name, or null. */
  fun tmhmIndex(item: ItemDef): Int? {
    val m = TMHM_NAME.matchEntire(item.name) ?: return null
    val n = m.groupValues[2].toInt()
    return when (m.groupValues[1]) {
      "TM" -> (n - 1).takeIf { n in 1..TM_COUNT }
      else -> (TM_COUNT + n - 1).takeIf { n in 1..HM_COUNT }
    }
  }

  fun tmhmMove(index: Int): Int? = tmhmMoves.getOrNull(index)

  fun isHmMove(moveId: Int): Boolean = moveId in tmhmMoves.subList(TM_COUNT, TM_COUNT + HM_COUNT)

  fun canLearnTmhm(dexId: Int, index: Int): Boolean =
      (tmhmBits[dexId] ?: 0L) shr index and 1L == 1L

  fun eggMoves(dexId: Int): List<Int> = eggMoves[dexId].orEmpty()

  /** The species an egg of [dexId] hatches into: the bottom of its evolution line. */
  fun babyOf(dexId: Int): Int {
    var cur = dexId
    var guard = 0
    while (guard++ < 4) cur = preEvolution[cur] ?: break
    // AlterEggSpeciesWithIncenseItem: without a held incense (nobody holds items here) these two
    // babies hatch as their evolved form.
    return when (cur) {
      AZURILL -> MARILL
      WYNAUT -> WOBBUFFET
      else -> cur
    }
  }

  private const val AZURILL = 298
  private const val MARILL = 183
  private const val WYNAUT = 360
  private const val WOBBUFFET = 202

  private val TMHM_NAME = Regex("(TM|HM)(\\d\\d)")

  private fun <V> pairs(packed: String, value: (String) -> V): Map<Int, V> =
      packed.split(';')
          .filter { it.isNotEmpty() }
          .associate { e ->
            val i = e.indexOf(':')
            e.substring(0, i).toInt() to value(e.substring(i + 1))
          }
}
