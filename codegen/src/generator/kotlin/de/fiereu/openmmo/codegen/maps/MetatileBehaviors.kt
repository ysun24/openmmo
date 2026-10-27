package de.fiereu.openmmo.codegen.maps

import de.fiereu.openmmo.common.enums.MetatileBehaviorNames
import de.fiereu.openmmo.common.enums.TileBehavior
import java.io.File
import java.util.Base64

/**
 * Resolves the [TileBehavior] of each tile from the decomp tileset metatile attributes, so the
 * server can tell grass from plain ground. Emerald packs attributes as 16-bit words (behavior in
 * bits 0-7), FireRed as 32-bit words (behavior in bits 0-8). Behaviors are classified by name so
 * the two games' differing numeric values do not matter.
 */
class MetatileBehaviors
private constructor(
    private val rootDir: File,
    private val primaryCount: Int,
    private val attrWidth: Int,
    private val behaviorMask: Int,
    private val idToCategory: Map<Int, TileBehavior>,
    private val attributePaths: Map<String, String>,
    private val tilesetAttributeSymbol: Map<String, String>,
) {

  // gTileset_X -> per local metatile index -> behavior ordinal, read from its .bin on first use.
  private val tilesetCache = HashMap<String, IntArray>()

  /** Base64 of one behavior ordinal byte per tile, in the same order as the block data. */
  fun behaviorData(
      primaryTileset: String?,
      secondaryTileset: String?,
      blockBytes: ByteArray
  ): String {
    val tileCount = blockBytes.size / 2
    if (tileCount == 0) return ""
    val primary = primaryTileset?.let { categories(it) } ?: IntArray(0)
    val secondary = secondaryTileset?.let { categories(it) } ?: IntArray(0)
    val out = ByteArray(tileCount)
    for (i in 0 until tileCount) {
      val raw =
          (blockBytes[i * 2].toInt() and 0xFF) or ((blockBytes[i * 2 + 1].toInt() and 0xFF) shl 8)
      val metatileId = raw and 0x3FF
      val ordinal =
          if (metatileId < primaryCount) primary.getOrNull(metatileId)
          else secondary.getOrNull(metatileId - primaryCount)
      out[i] = (ordinal ?: TileBehavior.NORMAL.ordinal).toByte()
    }
    return Base64.getEncoder().encodeToString(out)
  }

  private fun categories(tilesetName: String): IntArray =
      tilesetCache.getOrPut(tilesetName) {
        val symbol = tilesetAttributeSymbol[tilesetName] ?: return@getOrPut IntArray(0)
        val path = attributePaths[symbol] ?: return@getOrPut IntArray(0)
        val file = File(rootDir, path)
        if (!file.exists()) return@getOrPut IntArray(0)
        val bytes = file.readBytes()
        IntArray(bytes.size / attrWidth) { m ->
          var attr = 0
          for (b in 0 until attrWidth) {
            attr = attr or ((bytes[m * attrWidth + b].toInt() and 0xFF) shl (8 * b))
          }
          MetatileBehaviorNames.categorize(attr, attrWidth, idToCategory).ordinal
        }
      }

  companion object {
    fun read(rootDir: File): MetatileBehaviors {
      // Classified by name in common, shared with the web asset exporter.
      val idToCategory =
          MetatileBehaviorNames.idTable(File(rootDir, "include/constants/metatile_behaviors.h"))
      val (attributePaths, width) =
          parseAttributeIncbins(File(rootDir, "src/data/tilesets/metatiles.h"))
      val behaviorMask = if (width == 4) 0x1FF else 0xFF
      return MetatileBehaviors(
          rootDir = rootDir,
          primaryCount = readPrimaryCount(File(rootDir, "include/fieldmap.h")),
          attrWidth = width,
          behaviorMask = behaviorMask,
          idToCategory = idToCategory,
          attributePaths = attributePaths,
          tilesetAttributeSymbol =
              parseTilesetAttributes(File(rootDir, "src/data/tilesets/headers.h")),
      )
    }

    private fun readPrimaryCount(file: File): Int {
      if (!file.exists()) return 512
      val re = Regex("""#define\s+NUM_METATILES_IN_PRIMARY\s+(\d+)""")
      return file.readLines().firstNotNullOfOrNull {
        re.find(it.trim())?.groupValues?.get(1)?.toInt()
      } ?: 512
    }

    // gMetatileAttributes_X[] = INCBIN_U16/U32("path"). The width tells us the attribute size.
    private fun parseAttributeIncbins(file: File): Pair<Map<String, String>, Int> {
      if (!file.exists()) return emptyMap<String, String>() to 2
      val re = Regex("""(gMetatileAttributes_\w+)\[]\s*=\s*INCBIN_U(16|32)\("([^"]+)"\)""")
      val map = LinkedHashMap<String, String>()
      var width = 2
      for (m in re.findAll(file.readText())) {
        map[m.groupValues[1]] = m.groupValues[3]
        width = if (m.groupValues[2] == "32") 4 else 2
      }
      return map to width
    }

    // Walk the tileset structs and pair each gTileset_X with its metatileAttributes symbol.
    private fun parseTilesetAttributes(file: File): Map<String, String> {
      if (!file.exists()) return emptyMap()
      val tilesetRe = Regex("""const struct Tileset\s+(gTileset_\w+)""")
      val attrRe = Regex("""\.metatileAttributes\s*=\s*(gMetatileAttributes_\w+)""")
      val out = LinkedHashMap<String, String>()
      var current: String? = null
      for (line in file.readLines()) {
        tilesetRe.find(line)?.let { current = it.groupValues[1] }
        attrRe.find(line)?.let { m -> current?.let { out[it] = m.groupValues[1] } }
      }
      return out
    }

  }
}
