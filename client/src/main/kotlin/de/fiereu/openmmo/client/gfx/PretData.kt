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
    /** The server numbers this region's map groups from here (codegen's gbaBankOffset). */
    val bankOffset: Int,
    /** The text table under codegen/dialog. */
    val dialogRegion: String,
) {
  FIRERED("pokefirered", 640, 640, 7, 0, "kanto"),
  EMERALD("pokeemerald", 512, 512, 6, 50, "hoenn");

  /** Metatile attribute words: FireRed packs 32 bits, Emerald 16. */
  fun layerType(attr: Int): Int =
      if (this == FIRERED) (attr ushr 29) and 0x3 else (attr ushr 12) and 0xF

  fun behavior(attr: Int): Int = if (this == FIRERED) attr and 0x1FF else attr and 0xFF

  /** MB_COUNTER, the tile a shop clerk is talked to across. */
  val counterBehavior: Int
    get() = if (this == FIRERED) 0x80 else 0x80

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
    /** Raw `metatile_attributes.bin` words, one per metatile. */
    val attributes: IntArray = IntArray(0),
)

/** A map.json connection: [offset] in tiles along the shared edge. */
data class PretConnection(val direction: String, val offset: Int, val mapName: String)

class LayoutData(
    val name: String,
    val width: Int,
    val height: Int,
    /** Raw block words: metatile id in the low 10 bits, collision and elevation above. */
    val blocks: IntArray,
    val primary: Tileset,
    val secondary: Tileset,
    /** Border metatiles, drawn around the map where no connection is. */
    val border: IntArray = IntArray(0),
    val borderWidth: Int = 2,
    val borderHeight: Int = 2,
    val connections: List<PretConnection> = emptyList(),
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

  /** `gTileset_X` -> `gMetatileAttributes_X`. */
  private val attributeSymbols: Map<String, String> by lazy {
    val text = File(base, "src/data/tilesets/headers.h").readText()
    Regex("""const struct Tileset (\w+)\s*=\s*\{(.*?)\};""", RegexOption.DOT_MATCHES_ALL)
        .findAll(text)
        .mapNotNull { m ->
          Regex("""\.metatileAttributes\s*=\s*(\w+)""")
              .find(m.groupValues[2])
              ?.groupValues
              ?.get(1)
              ?.let { m.groupValues[1] to it }
        }
        .toMap()
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
              """const u(?:16|32) (gTileset\w+|gMetatiles\w+|gMetatileAttributes\w+)\[\](?:\[16\])?\s*=\s*(.*?);""",
              RegexOption.DOT_MATCHES_ALL)
          .findAll(text)
          .forEach { m ->
            out[m.groupValues[1]] =
                Regex("""INC(?:BIN|GFX)_U(?:16|32)\("([^"]+)"""")
                    .findAll(m.groupValues[2])
                    .map { it.groupValues[1] }
                    .toList()
          }
    }
    out
  }

  private val tilesetCache = mutableMapOf<String, Tileset>()
  private val layoutCache = mutableMapOf<Pair<Int, Int>, LayoutData?>()

  /** [bank] is the server's wire bank, the decomp group shifted by [GbaGame.bankOffset]. */
  fun mapName(bank: Int, map: Int): String? =
      groupOrder.getOrNull(bank - game.bankOffset)?.getOrNull(map)

  /** The wire (bank, map) of a map by its `MAP_*` constant or directory name. */
  fun addressOf(mapConst: String): Pair<Int, Int>? {
    for ((g, maps) in groupOrder.withIndex()) {
      for ((i, name) in maps.withIndex()) {
        if (name == mapConst || mapConstOf(name) == mapConst) return (g + game.bankOffset) to i
      }
    }
    return null
  }

  private val mapConsts = mutableMapOf<String, String?>()

  private fun mapConstOf(name: String): String? =
      mapConsts.getOrPut(name) {
        runCatching { parse("data/maps/$name/map.json").jsonObject["id"]?.jsonPrimitive?.content }
            .getOrNull()
      }

  fun layout(bank: Int, map: Int): LayoutData? =
      layoutCache.getOrPut(bank to map) {
        val name = mapName(bank, map) ?: return@getOrPut null
        val mapJson = parse("data/maps/$name/map.json").jsonObject
        val layoutId = mapJson.getValue("layout").jsonPrimitive.content
        val l = layouts[layoutId] ?: return@getOrPut null
        val width = l.getValue("width").jsonPrimitive.int
        val height = l.getValue("height").jsonPrimitive.int
        val blocks = readU16(File(base, l.getValue("blockdata_filepath").jsonPrimitive.content))
        val border =
            l["border_filepath"]
                ?.jsonPrimitive
                ?.content
                ?.let { File(base, it) }
                ?.takeIf { it.exists() }
                ?.let(::readU16) ?: IntArray(0)
        val connections =
            (mapJson["connections"] as? JsonArray).orEmpty().mapNotNull { c ->
              val o = c.jsonObject
              PretConnection(
                  o["direction"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                  o["offset"]?.jsonPrimitive?.int ?: 0,
                  o["map"]?.jsonPrimitive?.content ?: return@mapNotNull null)
            }
        LayoutData(
            name,
            width,
            height,
            blocks,
            tileset(l.getValue("primary_tileset").jsonPrimitive.content, primary = true),
            tileset(l.getValue("secondary_tileset").jsonPrimitive.content, primary = false),
            border,
            l["border_width"]?.jsonPrimitive?.int ?: 2,
            l["border_height"]?.jsonPrimitive?.int ?: 2,
            connections,
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
        val attrs =
            attributeSymbols[symbol]
                ?.let { incbins[it]?.firstOrNull() }
                ?.let { File(base, it) }
                ?.takeIf { it.exists() }
                ?.let { if (game == GbaGame.FIRERED) readU32(it) else readU16(it) } ?: IntArray(0)
        Tileset(tiles, palettes, metatiles, attrs)
      }

  private fun parse(path: String) = json.parseToJsonElement(File(base, path).readText())

  companion object {
    fun readU16(file: File): IntArray {
      val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
      return IntArray(buf.remaining() / 2) { buf.short.toInt() and 0xFFFF }
    }

    fun readU32(file: File): IntArray {
      val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
      return IntArray(buf.remaining() / 4) { buf.int }
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
