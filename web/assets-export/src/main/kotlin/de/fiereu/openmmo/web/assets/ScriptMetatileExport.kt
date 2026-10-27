package de.fiereu.openmmo.web.assets

import de.fiereu.openmmo.client.gfx.GbaGame
import de.fiereu.openmmo.client.gfx.MetatilePainter
import de.fiereu.openmmo.client.gfx.MetatilePainter.Pixels
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * r5-scripts: the metatiles map scripts swap in at run time (`setmetatile`: opened barriers, doors,
 * gym puzzles, cut grass...). The maps are pre-rendered images, so per map an atlas of every
 * metatile its scripts can set, drawn with that map's tilesets: cells of 16x16, row 0 the bottom
 * layer and row 1 the top layer (like the secret base atlases). Manifest `scriptTiles`: `{"<map
 * key>": {"atlas": png, "ids": [metatile ids in atlas order]}}`.
 */
class ScriptMetatileExport(private val decompRoot: File, private val out: Output) {
  private val setmetatile =
      Regex("""^\s*setmetatile\s+[^,]+,\s*[^,]+,\s*(\w+)""", RegexOption.MULTILINE)

  private fun metatileIds(game: GbaGame): Map<String, Int> {
    val f = File(decompRoot, "${game.dir}/include/constants/metatile_labels.h")
    if (!f.isFile) return emptyMap()
    return Regex("""#define\s+(METATILE_\w+)\s+(0x[0-9A-Fa-f]+|\d+)""")
        .findAll(f.readText())
        .associate {
          val v = it.groupValues[2]
          it.groupValues[1] to (if (v.startsWith("0x")) v.substring(2).toInt(16) else v.toInt())
        }
  }

  fun export(region: Int, game: GbaGame, entries: List<MapEntry>): JsonObject = buildJsonObject {
    val ids = metatileIds(game)
    for (e in entries) {
      val script = File(decompRoot, "${game.dir}/data/maps/${e.name}/scripts.inc")
      if (!script.isFile) continue
      val tiles =
          setmetatile
              .findAll(script.readText())
              .mapNotNull { m ->
                val a = m.groupValues[1]
                ids[a] ?: a.removePrefix("0x").toIntOrNull(if (a.startsWith("0x")) 16 else 10)
              }
              .distinct()
              .sorted()
              .toList()
      if (tiles.isEmpty()) continue
      val px = 16 * tiles.size
      val bottom = Pixels(px, 16)
      val top = Pixels(px, 16)
      tiles.forEachIndexed { i, t ->
        runCatching { MetatilePainter.drawMetatile(bottom, top, game, e.layout, t, i * 16, 0) }
      }
      val img = java.awt.image.BufferedImage(px, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB)
      img.setRGB(0, 0, px, 16, bottom.data, 0, px)
      img.setRGB(0, 16, px, 16, top.data, 0, px)
      val path = out.writePng("scripttiles/$region/${e.name.lowercase()}.png", img)
      put(
          e.key,
          buildJsonObject {
            put("atlas", path)
            put("ids", buildJsonArray { tiles.forEach { add(JsonPrimitive(it)) } })
          })
    }
  }
}
