package de.fiereu.openmmo.web.assets

import java.awt.image.BufferedImage
import java.io.File

/**
 * Pokemon front, back and party icon sprites keyed by national dex number, the species id the
 * server and the battle code use. Resolved through the decomp's own species tables (front/back pic
 * table, palette table, icon table and icon palette indices), not by guessing folder names.
 */
class PokemonExport(decompRoot: File, val gameDir: String) {
  private val d = Decomp(File(decompRoot, gameDir))
  val failures: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

  /** The three images of one species, all ARGB with colour 0 transparent. */
  class Images(val front: BufferedImage?, val back: BufferedImage?, val icon: BufferedImage?)

  /** `SPECIES_X` suffix -> internal species id. */
  private val speciesIds: Map<String, Int> by lazy {
    d.defines("include/constants/species.h", "SPECIES_").mapKeys { it.key.removePrefix("SPECIES_") }
  }

  /** `NATIONAL_DEX_X` suffix -> national number: an enum counting up from NONE = 0. */
  val nationalDex: Map<String, Int> by lazy {
    val out = LinkedHashMap<String, Int>()
    val re = Regex("""^NATIONAL_DEX_(\w+)\s*(?:=\s*(\d+))?,?""")
    var next = 0
    var inEnum = false
    for (raw in d.text("include/constants/pokedex.h").lines()) {
      val line = raw.trim()
      if (!inEnum) {
        if (line.startsWith("enum")) inEnum = true
        continue
      }
      if (line.startsWith("}")) break
      val m = re.find(line) ?: continue
      val id = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: next
      out.putIfAbsent(m.groupValues[1], id)
      next = id + 1
    }
    out
  }

  private fun macroTable(path: String, macro: String): Map<String, String> {
    val f = File(d.base, path)
    if (!f.isFile) return emptyMap()
    val out = LinkedHashMap<String, String>()
    Regex("""$macro\(\s*(\w+)\s*,\s*(\w+)\s*\)""").findAll(f.readText()).forEach {
      out.putIfAbsent(it.groupValues[1], it.groupValues[2])
    }
    return out
  }

  /** `[SPECIES_X] = value` entries of one array initialiser in [path]. */
  private fun designatedTable(path: String, array: String): Map<String, String> {
    val text = d.text(path)
    val start = Regex("""\b$array\s*\[\s*\]\s*=""").find(text) ?: return emptyMap()
    val end = text.indexOf("};", start.range.last)
    val body = text.substring(start.range.last, if (end < 0) text.length else end)
    val out = LinkedHashMap<String, String>()
    Regex("""\[SPECIES_(\w+)\]\s*=\s*(\w+)""").findAll(body).forEach {
      out.putIfAbsent(it.groupValues[1], it.groupValues[2])
    }
    return out
  }

  private val fronts by lazy {
    macroTable("src/data/pokemon_graphics/still_front_pic_table.h", "SPECIES_SPRITE").ifEmpty {
      macroTable("src/data/pokemon_graphics/front_pic_table.h", "SPECIES_SPRITE")
    }
  }
  private val backs by lazy {
    macroTable("src/data/pokemon_graphics/back_pic_table.h", "SPECIES_SPRITE")
  }
  private val palettes by lazy {
    macroTable("src/data/pokemon_graphics/palette_table.h", "SPECIES_PAL")
  }
  private val icons by lazy { designatedTable("src/pokemon_icon.c", "gMonIconTable") }
  private val iconPalIndex by lazy {
    designatedTable("src/pokemon_icon.c", "gMonIconPaletteIndices")
  }
  private val iconPalettes: List<IntArray?> by lazy {
    d.symbols["gMonIconPalettes"].orEmpty().map { Decomp.readPalette(d.file(it)) }
  }

  /** National dex -> species constant suffix, the first constant per number (UNOWN, not forms). */
  val species: Map<Int, String> by lazy {
    val out = sortedMapOf<Int, String>()
    speciesIds.entries
        .sortedBy { it.value }
        .forEach { (name, _) ->
          val dex = nationalDex[name] ?: return@forEach
          // The "old Unown" placeholders after Deoxys are not real species.
          if (dex > 0 && !name.startsWith("OLD_UNOWN")) out.putIfAbsent(dex, name)
        }
    out
  }

  private fun image(sym: String?, palette: IntArray?, w: Int, h: Int): BufferedImage? {
    val path = sym?.let { d.symbols[it]?.firstOrNull() } ?: return null
    val f = d.file(path) ?: return null
    val full = Decomp.readIndexed(f, palette)
    val img =
        if (full.width > w || full.height > h)
            full.getSubimage(0, 0, minOf(w, full.width), minOf(h, full.height))
        else full
    // getSubimage shares the raster; copy so the PNG encoder sees a plain image.
    val copy = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
    copy.createGraphics().apply {
      drawImage(img, 0, 0, null)
      dispose()
    }
    return copy.takeUnless(Decomp::isEmpty)
  }

  fun images(dex: Int): Images? {
    val name = species[dex] ?: return null
    return try {
      val palette =
          palettes[name]
              ?.let { d.symbols[it]?.firstOrNull() }
              ?.let { Decomp.readPalette(d.file(it)) }
      val iconPal = iconPalIndex[name]?.toIntOrNull()?.let { iconPalettes.getOrNull(it) }
      Images(
          image(fronts[name], palette, 64, 64),
          image(backs[name], palette, 64, 64),
          image(icons[name], iconPal, 32, 32))
    } catch (e: Exception) {
      failures += "$gameDir #$dex $name: ${e.javaClass.simpleName}: ${e.message}"
      null
    }
  }
}
