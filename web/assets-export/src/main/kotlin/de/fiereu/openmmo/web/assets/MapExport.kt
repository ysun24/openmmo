package de.fiereu.openmmo.web.assets

import de.fiereu.openmmo.client.gfx.GbaGame
import de.fiereu.openmmo.client.gfx.LayoutData
import de.fiereu.openmmo.client.gfx.MetatilePainter
import de.fiereu.openmmo.client.gfx.MetatilePainter.Pixels
import de.fiereu.openmmo.client.gfx.PretData
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One map of the server's map space, `region:bank:map` with its decomp layout. */
data class MapEntry(
    val region: Int,
    val bank: Int,
    val map: Int,
    val name: String,
    /** The layout constant, `LAYOUT_PALLET_TOWN`; maps sharing it share files. */
    val layoutId: String,
    val layout: LayoutData,
    val music: String?,
    /** (direction, offset, target key) for the four seamless edge connections. */
    val connections: List<Triple<String, Int, String>>,
) {
  val key = "$region:$bank:$map"
}

/** The PNGs and collision grid of one layout; maps sharing a layout share these files. */
data class LayoutFiles(
    val bottom: String,
    val top: String,
    val collision: String,
    val border: String?
)

class MapExport(private val decompRoot: File, private val out: Output) {
  private val json = Json { ignoreUnknownKeys = true }
  val failures: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

  /**
   * Every map of [game]. Banks and map numbers are positions in `map_groups.json`'s group_order,
   * shifted by the region's bank offset, which is what [PretData.mapName] and the server use.
   */
  fun collect(region: Int, game: GbaGame): List<MapEntry> {
    val pret = PretData(decompRoot, game)
    val base = File(decompRoot, game.dir)
    val entries = mutableListOf<MapEntry>()
    var bank = game.bankOffset
    while (pret.mapName(bank, 0) != null) {
      var map = 0
      while (true) {
        val name = pret.mapName(bank, map) ?: break
        val key = "$region:$bank:$map"
        try {
          val layout = pret.layout(bank, map)
          if (layout == null) {
            failures += "$key $name: layout not found"
          } else {
            val mapJson =
                json
                    .parseToJsonElement(File(base, "data/maps/$name/map.json").readText())
                    .jsonObject
            val music = mapJson["music"]?.jsonPrimitive?.contentOrNull
            val layoutId = mapJson["layout"]?.jsonPrimitive?.contentOrNull ?: name
            val conns =
                layout.connections.mapNotNull { c ->
                  if (c.direction !in DIRECTIONS) return@mapNotNull null
                  val addr = pret.addressOf(c.mapName)
                  if (addr == null) {
                    failures += "$key $name: connection target ${c.mapName} unknown"
                    return@mapNotNull null
                  }
                  Triple(c.direction, c.offset, "$region:${addr.first}:${addr.second}")
                }
            entries += MapEntry(region, bank, map, name, layoutId, layout, music, conns)
          }
        } catch (e: Exception) {
          failures += "$key $name: ${e.javaClass.simpleName}: ${e.message}"
        }
        map++
      }
      bank++
    }
    return entries
  }

  /** Renders one layout: bottom/top PNGs, its border block image and the collision grid. */
  fun render(region: Int, game: GbaGame, layoutId: String, layout: LayoutData): LayoutFiles {
    val w = layout.width
    val h = layout.height
    val bottom = Pixels(w * 16, h * 16)
    val top = Pixels(w * 16, h * 16)
    for (y in 0 until h) for (x in 0 until w) {
      val id = layout.metatileAt(x, y) ?: continue
      MetatilePainter.drawMetatile(bottom, top, game, layout, id, x * 16, y * 16)
    }
    val stem = "maps/$region/${layoutId.removePrefix("LAYOUT_").lowercase()}"
    val b = out.writePng("$stem.bottom.png", bottom.toImage())
    val t = out.writePng("$stem.top.png", top.toImage())
    val c = out.write("$stem.col.bin", collision(layout))
    val border =
        if (layout.border.size >= layout.borderWidth * layout.borderHeight &&
            layout.borderWidth > 0 &&
            layout.borderHeight > 0)
            out.writePng("$stem.border.png", border(game, layout).toImage())
        else null
    return LayoutFiles(b, t, c, border)
  }

  fun manifestEntry(e: MapEntry, files: LayoutFiles): JsonObject = buildJsonObject {
    put("name", e.name)
    put("width", e.layout.width)
    put("height", e.layout.height)
    put("bottom", files.bottom)
    put("top", files.top)
    put("collision", files.collision)
    put("border", files.border?.let(::JsonPrimitive) ?: JsonNull)
    put("borderWidth", e.layout.borderWidth)
    put("borderHeight", e.layout.borderHeight)
    put(
        "connections",
        buildJsonArray {
          e.connections.forEach { (dir, off, target) ->
            add(
                buildJsonObject {
                  put("dir", dir)
                  put("offset", off)
                  put("map", target)
                })
          }
        })
    put("music", e.music?.let(::JsonPrimitive) ?: JsonNull)
  }

  companion object {
    val DIRECTIONS = setOf("up", "down", "left", "right")

    /** bit0 = blocked (collision bits != 0), bits 4-7 = elevation; one byte per tile. */
    fun collision(layout: LayoutData): ByteArray =
        ByteArray(layout.width * layout.height) { i ->
          val b = layout.blocks.getOrElse(i) { 0x0C00 }
          val blocked = if ((b shr 10) and 0x3 != 0) 1 else 0
          val elevation = (b shr 12) and 0xF
          (blocked or (elevation shl 4)).toByte()
        }

    /** The border blocks, both layers flattened, [LayoutData.borderWidth] x height metatiles. */
    fun border(game: GbaGame, layout: LayoutData): Pixels {
      val bw = layout.borderWidth
      val bh = layout.borderHeight
      val bottom = Pixels(bw * 16, bh * 16)
      val top = Pixels(bw * 16, bh * 16)
      for (y in 0 until bh) for (x in 0 until bw) {
        val id = layout.border[y * bw + x] and 0x3FF
        MetatilePainter.drawMetatile(bottom, top, game, layout, id, x * 16, y * 16)
      }
      for (i in bottom.data.indices) if (top.data[i] ushr 24 != 0) bottom.data[i] = top.data[i]
      return bottom
    }
  }
}
