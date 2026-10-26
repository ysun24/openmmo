package de.fiereu.openmmo.client.gfx

import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Per-game constants from each decomp's `include/fieldmap.h`. */
enum class GbaGame(
    val dir: String,
    val tilesInPrimary: Int,
    val metatilesInPrimary: Int,
    val palsInPrimary: Int,
) {
  FIRERED("pokefirered", 640, 640, 7),
  EMERALD("pokeemerald", 512, 512, 6);

  companion object {
    /** Region ids as the server uses them: 0 is Kanto, 1 is Hoenn. */
    fun forRegion(regionId: Int): GbaGame? =
        when (regionId) {
          0 -> FIRERED
          1 -> EMERALD
          else -> null
        }
  }
}

/** One 8x8 tile reference inside a metatile, as packed in `metatiles.bin`. */
data class TileRef(val tile: Int, val hFlip: Boolean, val vFlip: Boolean, val palette: Int) {
  companion object {
    fun of(v: Int) = TileRef(v and 0x3FF, v and 0x400 != 0, v and 0x800 != 0, (v shr 12) and 0xF)
  }
}

class Tileset(
    /** 4bpp colour indices, one IntArray of 64 per 8x8 tile. */
    val tiles: List<IntArray>,
    /** Up to 16 palettes of 16 ARGB colours; slots this tileset does not own are null. */
    val palettes: Array<IntArray?>,
    /** 8 tile refs per metatile: 4 bottom layer then 4 top layer, each TL TR BL BR. */
    val metatiles: List<List<TileRef>>,
)

class LayoutData(
    val name: String,
    val width: Int,
    val height: Int,
    /** Raw block words: metatile id in the low 10 bits, collision and elevation above. */
    val blocks: IntArray,
    val primary: Tileset,
    val secondary: Tileset,
) {
  fun metatileAt(x: Int, y: Int): Int? =
      if (x in 0 until width && y in 0 until height) blocks[y * width + x] and 0x3FF else null
}

/**
 * Reads maps straight out of a pret decomp checkout: the same data the game is built from, so the
 * client needs no ROM and ships none of anyone's client.
 */
class PretData(private val root: File, val game: GbaGame) {
  private val json = Json { ignoreUnknownKeys = true }
  private val base = File(root, game.dir)

  private val groupOrder: List<List<String>> by lazy {
    val g = parse("data/maps/map_groups.json").jsonObject
    g.getValue("group_order").jsonArray.map { group ->
      g.getValue(group.jsonPrimitive.content).jsonArray.map { it.jsonPrimitive.content }
    }
  }

  private val layouts: Map<String, JsonObject> by lazy {
    parse("data/layouts/layouts.json")
        .jsonObject
        .getValue("layouts")
        .jsonArray
        .map { it.jsonObject }
        .filter { "id" in it }
        .associateBy { it.getValue("id").jsonPrimitive.content }
  }

  /** `gTileset_X` -> (tiles symbol, palettes symbol, metatiles symbol). */
  private val tilesetHeaders: Map<String, Triple<String, String, String>> by lazy {
    val text = File(base, "src/data/tilesets/headers.h").readText()
    Regex("""const struct Tileset (\w+)\s*=\s*\{(.*?)\};""", RegexOption.DOT_MATCHES_ALL)
        .findAll(text)
        .associate { m ->
          val body = m.groupValues[2]
          fun sym(field: String) =
              Regex("""\.$field\s*=\s*(\w+)""").find(body)?.groupValues?.get(1) ?: ""
          m.groupValues[1] to Triple(sym("tiles"), sym("palettes"), sym("metatiles"))
        }
  }

  /** Every INCBIN in the tileset sources, keyed by symbol, with all paths it pulls in. */
  private val incbins: Map<String, List<String>> by lazy {
    val files =
        listOf("src/graphics.c", "src/data/tilesets/graphics.h", "src/data/tilesets/metatiles.h")
            .map { File(base, it) }
            .filter { it.exists() }
    val out = mutableMapOf<String, List<String>>()
    for (f in files) {
      val text = f.readText()
      Regex(
              """const u(?:16|32) (gTileset\w+|gMetatiles\w+)\[\](?:\[16\])?\s*=\s*(.*?);""",
              RegexOption.DOT_MATCHES_ALL)
          .findAll(text)
          .forEach { m ->
            out[m.groupValues[1]] =
                Regex("""INCBIN_U(?:16|32)\("([^"]+)"\)""")
                    .findAll(m.groupValues[2])
                    .map { it.groupValues[1] }
                    .toList()
          }
    }
    out
  }

  private val tilesetCache = mutableMapOf<String, Tileset>()
  private val layoutCache = mutableMapOf<Pair<Int, Int>, LayoutData?>()

  fun mapName(bank: Int, map: Int): String? = groupOrder.getOrNull(bank)?.getOrNull(map)

  fun layout(bank: Int, map: Int): LayoutData? =
      layoutCache.getOrPut(bank to map) {
        val name = mapName(bank, map) ?: return@getOrPut null
        val mapJson = parse("data/maps/$name/map.json").jsonObject
        val layoutId = mapJson.getValue("layout").jsonPrimitive.content
        val l = layouts[layoutId] ?: return@getOrPut null
        val width = l.getValue("width").jsonPrimitive.int
        val height = l.getValue("height").jsonPrimitive.int
        val blocks = readU16(File(base, l.getValue("blockdata_filepath").jsonPrimitive.content))
        LayoutData(
            name,
            width,
            height,
            blocks,
            tileset(l.getValue("primary_tileset").jsonPrimitive.content, primary = true),
            tileset(l.getValue("secondary_tileset").jsonPrimitive.content, primary = false),
        )
      }

  private fun tileset(symbol: String, primary: Boolean): Tileset =
      tilesetCache.getOrPut(symbol) {
        val (tilesSym, palSym, metaSym) =
            tilesetHeaders[symbol] ?: error("tileset $symbol not in headers.h")
        val tilesPath =
            incbins[tilesSym]?.firstOrNull()?.replace(Regex("""\.4bpp(\.lz)?$"""), ".png")
                ?: error("no tiles for $symbol")
        val tiles = readTiles(File(base, tilesPath))
        val palettes = arrayOfNulls<IntArray>(16)
        incbins[palSym].orEmpty().forEachIndexed { i, p ->
          val pal = File(base, p.replace(".gbapal", ".pal"))
          if (pal.exists()) palettes[i] = readJascPalette(pal)
        }
        val metaPath = incbins[metaSym]?.firstOrNull() ?: error("no metatiles for $symbol")
        val words = readU16(File(base, metaPath))
        val metatiles = words.toList().chunked(8).map { chunk -> chunk.map(TileRef::of) }
        Tileset(tiles, palettes, metatiles)
      }

  private fun parse(path: String) = json.parseToJsonElement(File(base, path).readText())

  companion object {
    fun readU16(file: File): IntArray {
      val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
      return IntArray(buf.remaining() / 2) { buf.short.toInt() and 0xFFFF }
    }

    /** Splits an indexed 4bpp sheet into 8x8 tiles, reading raw colour indices. */
    fun readTiles(file: File): List<IntArray> {
      val img = ImageIO.read(file)
      val raster = img.raster
      val cols = img.width / 8
      val rows = img.height / 8
      val px = IntArray(1)
      return List(cols * rows) { t ->
        val tx = (t % cols) * 8
        val ty = (t / cols) * 8
        IntArray(64) { i -> raster.getPixel(tx + i % 8, ty + i / 8, px)[0] and 0xF }
      }
    }

    fun readJascPalette(file: File): IntArray {
      val lines = file.readLines().drop(3).filter { it.isNotBlank() }
      return IntArray(16) { i ->
        val (r, g, b) =
            lines.getOrNull(i)?.trim()?.split(Regex("\\s+"))?.map(String::toInt) ?: listOf(0, 0, 0)
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
      }
    }

    /** Reads an indexed sprite sheet to ARGB, colour index 0 transparent. */
    fun readSprite(file: File): BufferedImage {
      val img = ImageIO.read(file)
      val cm = img.colorModel as? java.awt.image.IndexColorModel
      val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
      val px = IntArray(1)
      for (y in 0 until img.height) for (x in 0 until img.width) {
        if (cm == null) {
          out.setRGB(x, y, img.getRGB(x, y))
          continue
        }
        val idx = img.raster.getPixel(x, y, px)[0]
        if (idx != 0) out.setRGB(x, y, cm.getRGB(idx) or (0xFF shl 24))
      }
      return out
    }

    @Suppress("unused") private fun JsonArray.strings() = map { it.jsonPrimitive.content }
  }
}
