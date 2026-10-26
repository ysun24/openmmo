package de.fiereu.openmmo.web.assets

import de.fiereu.openmmo.client.gfx.GbaGame
import java.awt.image.BufferedImage
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Overworld object sprites, keyed by the `OBJ_EVENT_GFX_*` value the server sends as a NPC's
 * graphicsId (its map codegen reads the same `include/constants/event_objects.h`). Reads the same
 * tables as the client's ObjectEventGraphics, and also the palette tag, since a sheet PNG's own
 * palette is not always the one the game loads for it, and pic tables that mix several sheets.
 *
 * Every exported sheet is one row of the pic table's frames in table order, each [frameW] x
 * [frameH]: 0 face down, 1 face up, 2 face left, 3-4 walk down, 5-6 walk up, 7-8 walk left and, for
 * the player sprites, 9-17 the same nine for running. Right is left mirrored.
 */
class ObjectExport(decompRoot: File, private val game: GbaGame, private val out: Output) {
  private val d = Decomp(File(decompRoot, game.dir))
  private val dir = "src/data/object_events"
  val failures: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

  data class Info(val width: Int, val height: Int, val images: String, val paletteTag: String?)

  data class Frame(val pic: String, val wTiles: Int, val hTiles: Int, val index: Int)

  data class Sprite(
      val gfx: Int,
      val name: String,
      val sheet: String,
      val w: Int,
      val h: Int,
      val n: Int
  )

  /** `OBJ_EVENT_GFX_*` value -> name; the first name wins for aliases. */
  val gfxIds: Map<Int, String> by lazy {
    val out = sortedMapOf<Int, String>()
    d.defines("include/constants/event_objects.h", "OBJ_EVENT_GFX_").forEach { (name, id) ->
      if (!name.startsWith("NUM_") && name != "OBJ_EVENT_GFX_VARS") out.putIfAbsent(id, name)
    }
    out
  }

  private val pointers: Map<String, String> by lazy {
    Regex("""\[(OBJ_EVENT_GFX_\w+)\]\s*=\s*&(\w+)""")
        .findAll(d.text("$dir/object_event_graphics_info_pointers.h"))
        .associate { it.groupValues[1] to it.groupValues[2] }
  }

  private val infos: Map<String, Info> by lazy {
    Regex("""ObjectEventGraphicsInfo\s+(\w+)\s*=\s*\{(.*?)\};""", RegexOption.DOT_MATCHES_ALL)
        .findAll(d.text("$dir/object_event_graphics_info.h"))
        .mapNotNull { m ->
          val body = m.groupValues[2]
          fun field(name: String) = Regex("""\.$name\s*=\s*(\w+)""").find(body)?.groupValues?.get(1)
          val w = field("width")?.toIntOrNull() ?: return@mapNotNull null
          val h = field("height")?.toIntOrNull() ?: return@mapNotNull null
          val images = field("images") ?: return@mapNotNull null
          m.groupValues[1] to Info(w, h, images, field("paletteTag"))
        }
        .toMap()
  }

  private val picTables: Map<String, List<Frame>> by lazy {
    Regex("""SpriteFrameImage\s+(\w+)\[\]\s*=\s*\{(.*?)\};""", RegexOption.DOT_MATCHES_ALL)
        .findAll(
            listOf("object_event_pic_tables.h", "berry_tree_graphics_tables.h")
                .map { File(d.base, "$dir/$it") }
                .filter { it.isFile }
                .joinToString("\n") { it.readText() })
        .associate { m ->
          val frames =
              Regex(
                      """overworld_frame\((\w+),\s*(\d+),\s*(\d+),\s*(\d+)\)|obj_frame_tiles\((\w+)\)""")
                  .findAll(m.groupValues[2])
                  .map { f ->
                    if (f.groupValues[1].isNotEmpty())
                        Frame(
                            f.groupValues[1],
                            f.groupValues[2].toInt(),
                            f.groupValues[3].toInt(),
                            f.groupValues[4].toInt())
                    else Frame(f.groupValues[5], 0, 0, 0)
                  }
                  .toList()
          m.groupValues[1] to frames
        }
  }

  /** `OBJ_EVENT_PAL_TAG_*` -> palette symbol, from event_object_movement.c's palette table. */
  private val paletteSymbols: Map<String, String> by lazy {
    Regex("""\{\s*(gObjectEventPal_\w+)\s*,\s*(OBJ_EVENT_PAL_TAG_\w+)\s*\}""")
        .findAll(d.text("src/event_object_movement.c"))
        .associate { it.groupValues[2] to it.groupValues[1] }
  }

  private val playerNames =
      when (game) {
        GbaGame.FIRERED -> "OBJ_EVENT_GFX_RED_NORMAL" to "OBJ_EVENT_GFX_GREEN_NORMAL"
        GbaGame.EMERALD -> "OBJ_EVENT_GFX_BRENDAN_NORMAL" to "OBJ_EVENT_GFX_MAY_NORMAL"
      }

  fun playerGfx(female: Boolean): Int? {
    val name = if (female) playerNames.second else playerNames.first
    return gfxIds.entries.firstOrNull { it.value == name }?.key
  }

  /** Exports every resolvable gfx id; returns them in id order. */
  fun exportAll(region: Int): List<Sprite> =
      gfxIds.mapNotNull { (id, name) ->
        try {
          export(region, id, name)
        } catch (e: Exception) {
          failures += "$region:$id $name: ${e.javaClass.simpleName}: ${e.message}"
          null
        }
      }

  private val sheetCache = HashMap<String, BufferedImage>()

  private fun sheetImage(path: String, palette: IntArray?): BufferedImage? {
    val f = d.file(path) ?: return null
    return sheetCache.getOrPut("$path|${palette?.contentHashCode()}") {
      Decomp.readIndexed(f, palette)
    }
  }

  private fun export(region: Int, id: Int, name: String): Sprite? {
    val infoSym = pointers[name] ?: return null.also { failures += "$region:$id $name: no pointer" }
    val info = infos[infoSym] ?: return null.also { failures += "$region:$id $name: no info" }
    val table = picTables[info.images].orEmpty()
    if (table.isEmpty()) return null.also { failures += "$region:$id $name: empty pic table" }
    val palette =
        info.paletteTag
            ?.let(paletteSymbols::get)
            ?.let { d.symbols[it]?.firstOrNull() }
            ?.let { d.file(it) }
            ?.let(Decomp::readPalette)
    val w = info.width
    val h = info.height
    val sheet = BufferedImage(w * table.size, h, BufferedImage.TYPE_INT_ARGB)
    val g = sheet.createGraphics()
    for ((i, f) in table.withIndex()) {
      val path = d.symbols[f.pic]?.firstOrNull()
      val src = path?.let { sheetImage(it, palette) }
      if (src == null) {
        failures += "$region:$id $name: pic ${f.pic} missing"
        g.dispose()
        return null
      }
      val fw = if (f.wTiles > 0) f.wTiles * 8 else src.width
      val fh = if (f.hTiles > 0) f.hTiles * 8 else src.height
      val cols = (src.width / fw).coerceAtLeast(1)
      val sx = (f.index % cols) * fw
      val sy = (f.index / cols) * fh
      val cw = minOf(fw, w, src.width - sx)
      val ch = minOf(fh, h, src.height - sy)
      if (cw <= 0 || ch <= 0) continue
      g.drawImage(src, i * w, 0, i * w + cw, ch, sx, sy, sx + cw, sy + ch, null)
    }
    g.dispose()
    val rel = out.writePng("objects/$region/$id.png", sheet)
    return Sprite(id, name, rel, w, h, table.size)
  }

  companion object {
    /** Frame layout for a sheet of [n] frames; missing frames fall back like the desktop does. */
    fun manifestEntry(s: Sprite): JsonObject = buildJsonObject {
      put("name", s.name)
      put("sheet", s.sheet)
      put("frameW", s.w)
      put("frameH", s.h)
      put("count", s.n)
      val n = s.n
      fun stand(i: Int) = if (i < n) i else 0
      fun walk(i: Int, standing: Int) = buildJsonArray {
        add(kotlinx.serialization.json.JsonPrimitive(if (i < n) i else standing))
        add(kotlinx.serialization.json.JsonPrimitive(if (i + 1 < n) i + 1 else standing))
      }
      put(
          "frames",
          buildJsonObject {
            put("down", stand(0))
            put("up", stand(1))
            put("left", stand(2))
            put("walkDown", walk(3, stand(0)))
            put("walkUp", walk(5, stand(1)))
            put("walkLeft", walk(7, stand(2)))
            if (n >= 18) {
              put("runDown", 9)
              put("runUp", 12)
              put("runLeft", 15)
              put("runWalkDown", walk(10, 9))
              put("runWalkUp", walk(13, 12))
              put("runWalkLeft", walk(16, 15))
            }
          })
      put("mirrorRight", true)
    }
  }
}
