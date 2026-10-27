package de.fiereu.openmmo.web.assets

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The games' own Latin fonts (`graphics/fonts/latin_*.png` + the glyph width tables in C), exported
 * twice from the same pixels:
 * - `<name>.png` + `<name>.json`: a bitmap atlas for the canvas (red = glyph pixel, green = the
 *   font's drop shadow pixel) with each Unicode code point's glyph index and advance width;
 * - `<name>.ttf`: a TrueType font whose outlines are the glyph's pixels as squares (64 units per
 *   GBA pixel, 16 pixels per em), so the DOM text boxes draw the same letters crisply at
 *   `font-size: 16 * k px`. Code points the font lacks fall back to the next CSS family.
 *
 * Code points come from the decomp's `charmap.txt` (`'A' = BB`); glyphs with no Unicode meaning
 * (PK, MN, LV, the super-script "er"/"re"/"e", ...) are additionally reachable at U+E000 + byte.
 */
class FontExport(private val decompRoot: File, private val out: Output) {
  val failures: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

  /** One exported font. [baseline] = first row below the letters (pixel rows from the cell top). */
  data class Spec(
      val name: String,
      val game: String,
      val png: String,
      val source: String,
      val widths: String,
      val baseline: Int,
      val lineHeight: Int,
      val family: String,
  )

  companion object {
    val SPECS =
        listOf(
            Spec(
                "normal",
                "pokefirered",
                "graphics/fonts/latin_normal.png",
                "src/text.c",
                "sFontNormalLatinGlyphWidths",
                13,
                16,
                "OpenMMO FRLG"),
            Spec(
                "normal",
                "pokeemerald",
                "graphics/fonts/latin_normal.png",
                "src/fonts.c",
                "gFontNormalLatinGlyphWidths",
                13,
                16,
                "OpenMMO RSE"),
            Spec(
                "small",
                "pokeemerald",
                "graphics/fonts/latin_small.png",
                "src/fonts.c",
                "gFontSmallLatinGlyphWidths",
                12,
                13,
                "OpenMMO Small"),
        )

    /** Unicode stand-ins for game glyphs the charmap names but gives no character. */
    private val EXTRA =
        mapOf(
            0x79 to listOf('↑'.code),
            0x7A to listOf('↓'.code),
            0x7B to listOf('←'.code),
            0x7C to listOf('→'.code),
            0xB7 to listOf('₽'.code), // the Pokedollar sign sits on the yen code
            0xB1 to listOf('"'.code), // straight double quote -> opening quote
            0xB4 to listOf('`'.code),
        )

    const val UNITS = 64 // font units per GBA pixel
    const val CELL = 16
  }

  /** `'x' = NN` single-byte Latin entries of a charmap, up to the Japanese section. */
  private fun charmap(game: String): Map<Int, Int> {
    val out = LinkedHashMap<Int, Int>()
    val re = Regex("""^'(\\?.)'\s*=\s*([0-9A-Fa-f]{2})\s*$""")
    for (raw in File(decompRoot, "$game/charmap.txt").readLines()) {
      val line = raw.substringBefore("@").trim()
      val m = re.find(line) ?: continue
      var ch = m.groupValues[1]
      if (ch.startsWith("\\")) ch = ch.substring(1)
      val cp = ch.codePointAt(0)
      // The Japanese block repeats the byte values with kana; Latin glyphs end there.
      if (cp in 0x3040..0x30FF) break
      out.putIfAbsent(cp, m.groupValues[2].toInt(16))
    }
    return out
  }

  private fun widths(game: String, path: String, array: String): IntArray {
    val text = File(decompRoot, "$game/$path").readText()
    val start = Regex("""\b$array\s*\[\s*\]\s*=\s*\{""").find(text) ?: error("no $array in $path")
    val end = text.indexOf("};", start.range.last)
    return Regex("""\d+""")
        .findAll(text.substring(start.range.last + 1, end).replace(Regex("//.*"), ""))
        .map { it.value.toInt() }
        .toList()
        .toIntArray()
  }

  /** Exports all [SPECS]; returns the manifest's `ui.fonts` object. */
  fun export(): JsonObject = buildJsonObject {
    for (s in SPECS) {
      runCatching { exportOne(s) }
          .onSuccess { put("${s.game}/${s.name}", it) }
          .onFailure { failures += "font ${s.game}/${s.name}: $it" }
    }
  }

  private fun exportOne(s: Spec): JsonObject {
    val png = javax.imageio.ImageIO.read(File(decompRoot, "${s.game}/${s.png}"))
    val raster = png.raster
    val w = widths(s.game, s.source, s.widths)
    // The latin glyph tables address 256 glyphs (bytes 0x00..0xFF); EOS (0xFF) is never drawn.
    val n = minOf(256, w.size, (png.width / CELL) * (png.height / CELL))
    val cols = png.width / CELL
    // palette index per pixel: 0 = transparent, 1 = glyph, 2 = shadow, 3 = cell background
    fun px(g: Int, x: Int, y: Int): Int =
        raster.getSample((g % cols) * CELL + x, (g / cols) * CELL + y, 0)

    val cmap = charmap(s.game).toMutableMap()
    for ((b, cps) in EXTRA) cps.forEach { cmap.putIfAbsent(it, b) }
    for (b in 0 until n) if (b != 0xFF) cmap.putIfAbsent(0xE000 + b, b)
    cmap.entries.removeIf { it.value >= n || it.value == 0xFF }

    // atlas: 16 columns of 16x16 cells, R = glyph, G = shadow
    val atlas = BufferedImage(16 * CELL, ((n + 15) / 16) * CELL, BufferedImage.TYPE_INT_ARGB)
    for (g in 0 until n) for (y in 0 until CELL) for (x in 0 until CELL) {
      val c =
          when (px(g, x, y)) {
            1 -> 0xFFFF0000.toInt()
            2 -> 0xFF00FF00.toInt()
            else -> 0
          }
      if (c != 0) atlas.setRGB((g % 16) * CELL + x, (g / 16) * CELL + y, c)
    }
    val base = "fonts/${s.game.removePrefix("poke")}-${s.name}"
    val atlasPath = out.writePng("$base.png", atlas)

    // TrueType from the glyph pixels (palette index 1 only; the CSS draws the shadow)
    val glyphs = (0 until n).map { g -> PixelGlyph(w[g], rects(g, ::px), s.baseline) }
    val ttf = TrueType.build(s.family, glyphs, cmap, s.baseline, CELL - s.baseline)
    val ttfPath = out.write("$base.ttf", ttf)

    val meta = buildJsonObject {
      put("family", s.family)
      put("cell", CELL)
      put("baseline", s.baseline)
      put("lineHeight", s.lineHeight)
      put("image", atlasPath)
      put("ttf", ttfPath)
      putJsonObject("glyphs") {
        cmap.entries
            .sortedBy { it.key }
            .forEach { (cp, b) ->
              putJsonArray("$cp") {
                add(kotlinx.serialization.json.JsonPrimitive(b))
                add(kotlinx.serialization.json.JsonPrimitive(w[b]))
              }
            }
      }
    }
    return buildJsonObject {
      put("json", out.write("$base.json", meta.toString().toByteArray()))
      put("image", atlasPath)
      put("ttf", ttfPath)
      put("family", s.family)
      put("baseline", s.baseline)
      put("lineHeight", s.lineHeight)
    }
  }

  /** Glyph pixels as rectangles: horizontal runs per row, merged downwards while identical. */
  private fun rects(g: Int, px: (Int, Int, Int) -> Int): List<IntArray> {
    val done = mutableListOf<IntArray>()
    var open = mutableListOf<IntArray>() // x0, x1, y0, y1 (exclusive)
    for (y in 0 until CELL) {
      val runs = mutableListOf<Pair<Int, Int>>()
      var x = 0
      while (x < CELL) {
        if (px(g, x, y) == 1) {
          val s = x
          while (x < CELL && px(g, x, y) == 1) x++
          runs += s to x
        } else x++
      }
      val next = mutableListOf<IntArray>()
      for ((a, b) in runs) {
        val r = open.firstOrNull { it[0] == a && it[1] == b }
        if (r != null) {
          r[3] = y + 1
          next += r
          open.remove(r)
        } else next += intArrayOf(a, b, y, y + 1)
      }
      done += open
      open = next
    }
    done += open
    return done
  }

  class PixelGlyph(val advance: Int, val rects: List<IntArray>, val baseline: Int)

  /** A minimal, sanitizer-clean TrueType writer for rectangle-only glyphs. */
  object TrueType {
    fun build(
        family: String,
        glyphs: List<PixelGlyph>,
        cmap: Map<Int, Int>,
        ascentPx: Int,
        descentPx: Int,
    ): ByteArray {
      val u = UNITS
      // glyph 0 = .notdef (empty), glyph i + 1 = game glyph i
      val gl = mutableListOf<ByteArray>()
      val adv = mutableListOf<Int>()
      val lsb = mutableListOf<Int>()
      var xMinAll = Int.MAX_VALUE
      var yMinAll = Int.MAX_VALUE
      var xMaxAll = Int.MIN_VALUE
      var yMaxAll = Int.MIN_VALUE
      var maxPts = 0
      var maxCont = 0
      gl += ByteArray(0)
      adv += 6 * u
      lsb += 0
      for (g in glyphs) {
        adv += g.advance * u
        if (g.rects.isEmpty()) {
          gl += ByteArray(0)
          lsb += 0
          continue
        }
        // pixel (x, row) -> units: x * u, (baseline - row) * u
        val boxes =
            g.rects.map {
              intArrayOf(it[0] * u, (g.baseline - it[3]) * u, it[1] * u, (g.baseline - it[2]) * u)
            }
        val xMin = boxes.minOf { it[0] }
        val yMin = boxes.minOf { it[1] }
        val xMax = boxes.maxOf { it[2] }
        val yMax = boxes.maxOf { it[3] }
        xMinAll = minOf(xMinAll, xMin)
        yMinAll = minOf(yMinAll, yMin)
        xMaxAll = maxOf(xMaxAll, xMax)
        yMaxAll = maxOf(yMaxAll, yMax)
        maxPts = maxOf(maxPts, boxes.size * 4)
        maxCont = maxOf(maxCont, boxes.size)
        lsb += xMin
        val b = ByteArrayOutputStream()
        val d = DataOutputStream(b)
        d.writeShort(boxes.size)
        d.writeShort(xMin)
        d.writeShort(yMin)
        d.writeShort(xMax)
        d.writeShort(yMax)
        boxes.forEachIndexed { i, _ -> d.writeShort(i * 4 + 3) }
        d.writeShort(0) // no instructions
        repeat(boxes.size * 4) { d.writeByte(1) } // on-curve, long coordinates
        // clockwise: (x0,y0) -> (x0,y1) -> (x1,y1) -> (x1,y0)
        val pts = boxes.flatMap {
          listOf(it[0] to it[1], it[0] to it[3], it[2] to it[3], it[2] to it[1])
        }
        var px = 0
        for ((x, _) in pts) {
          d.writeShort(x - px)
          px = x
        }
        var py = 0
        for ((_, y) in pts) {
          d.writeShort(y - py)
          py = y
        }
        gl += b.toByteArray()
      }
      if (xMinAll == Int.MAX_VALUE) {
        xMinAll = 0
        yMinAll = 0
        xMaxAll = 0
        yMaxAll = 0
      }
      val numGlyphs = gl.size

      // glyf + loca (long offsets, 4-byte aligned)
      val glyf = ByteArrayOutputStream()
      val loca = ByteArrayOutputStream()
      val locaD = DataOutputStream(loca)
      for (g in gl) {
        locaD.writeInt(glyf.size())
        glyf.write(g)
        while (glyf.size() % 4 != 0) glyf.write(0)
      }
      locaD.writeInt(glyf.size())

      val ascent = ascentPx * u
      val descent = descentPx * u
      val maxAdv = adv.max()

      val head = table {
        writeInt(0x00010000)
        writeInt(0x00010000)
        writeInt(0) // checkSumAdjustment, patched below
        writeInt(0x5F0F3CF5)
        writeShort(0x000B)
        writeShort(CELL * u)
        writeLong(0)
        writeLong(0)
        writeShort(xMinAll)
        writeShort(yMinAll)
        writeShort(xMaxAll)
        writeShort(yMaxAll)
        writeShort(0)
        writeShort(8)
        writeShort(2)
        writeShort(1) // long loca
        writeShort(0)
      }
      val hhea = table {
        writeInt(0x00010000)
        writeShort(ascent)
        writeShort(-descent)
        writeShort(0)
        writeShort(maxAdv)
        writeShort(lsb.min())
        writeShort(0)
        writeShort(xMaxAll)
        writeShort(1)
        writeShort(0)
        writeShort(0)
        repeat(4) { writeShort(0) }
        writeShort(0)
        writeShort(numGlyphs)
      }
      val maxp = table {
        writeInt(0x00010000)
        writeShort(numGlyphs)
        writeShort(maxPts)
        writeShort(maxCont)
        writeShort(0)
        writeShort(0)
        writeShort(2)
        repeat(7) { writeShort(0) }
        writeShort(0)
      }
      val hmtx = table {
        for (i in 0 until numGlyphs) {
          writeShort(adv[i])
          writeShort(lsb[i])
        }
      }
      val sorted = cmap.entries.filter { it.key <= 0xFFFF }.sortedBy { it.key }
      val os2 = table {
        writeShort(4)
        writeShort(adv.drop(1).filter { it > 0 }.average().toInt())
        writeShort(400)
        writeShort(5)
        writeShort(0)
        repeat(4) { writeShort(CELL * u / 2) } // subscript x/y size, offsets
        writeShort(0)
        writeShort(0)
        repeat(2) { writeShort(CELL * u / 2) } // superscript size
        writeShort(0)
        writeShort(CELL * u / 2)
        writeShort(u) // strikeout size
        writeShort(4 * u)
        writeShort(0) // family class
        repeat(10) { writeByte(0) }
        writeInt(0x00000003) // Basic Latin + Latin-1 supplement
        writeInt(0)
        writeInt(0)
        writeInt(0)
        writeBytes("OMMO")
        writeShort(0x00C0) // REGULAR | USE_TYPO_METRICS
        writeShort(sorted.first().key)
        writeShort(sorted.last().key)
        writeShort(ascent)
        writeShort(-descent)
        writeShort(0)
        writeShort(ascent)
        writeShort(descent)
        writeInt(1)
        writeInt(0)
        writeShort(7 * u)
        writeShort(10 * u)
        writeShort(0)
        writeShort(0x20)
        writeShort(1)
      }
      val cmapT = cmapTable(sorted.map { it.key to it.value + 1 })
      val name = nameTable(family)
      val post = table {
        writeInt(0x00030000)
        writeInt(0)
        writeShort(-u * 2)
        writeShort(u)
        writeInt(0)
        repeat(4) { writeInt(0) }
      }
      val tables =
          sortedMapOf(
              "OS/2" to os2,
              "cmap" to cmapT,
              "glyf" to glyf.toByteArray(),
              "head" to head,
              "hhea" to hhea,
              "hmtx" to hmtx,
              "loca" to loca.toByteArray(),
              "maxp" to maxp,
              "name" to name,
              "post" to post,
          )
      return assemble(tables)
    }

    private fun table(f: DataOutputStream.() -> Unit): ByteArray {
      val b = ByteArrayOutputStream()
      DataOutputStream(b).f()
      return b.toByteArray()
    }

    /** Format 4 subtable (BMP), referenced by both the Unicode and the Windows records. */
    private fun cmapTable(map: List<Pair<Int, Int>>): ByteArray {
      // contiguous runs where both code point and glyph id step by one
      val segs = mutableListOf<IntArray>() // start, end, delta
      for ((cp, gid) in map) {
        val last = segs.lastOrNull()
        if (last != null && cp == last[1] + 1 && gid - cp == last[2]) last[1] = cp
        else segs += intArrayOf(cp, cp, gid - cp)
      }
      segs += intArrayOf(0xFFFF, 0xFFFF, 1)
      val segX2 = segs.size * 2
      var pow = 1
      var log = 0
      while (pow * 2 <= segs.size) {
        pow *= 2
        log++
      }
      val sub = table {
        writeShort(4)
        writeShort(16 + segs.size * 8)
        writeShort(0)
        writeShort(segX2)
        writeShort(pow * 2)
        writeShort(log)
        writeShort(segX2 - pow * 2)
        segs.forEach { writeShort(it[1]) }
        writeShort(0)
        segs.forEach { writeShort(it[0]) }
        segs.forEach { writeShort(it[2] and 0xFFFF) }
        segs.forEach { writeShort(0) }
      }
      return table {
        writeShort(0)
        writeShort(2)
        writeShort(0)
        writeShort(3)
        writeInt(4 + 16)
        writeShort(3)
        writeShort(1)
        writeInt(4 + 16)
        write(sub)
      }
    }

    private fun nameTable(family: String): ByteArray {
      val recs =
          listOf(
              1 to family,
              2 to "Regular",
              3 to "$family;Regular",
              4 to family,
              5 to "Version 1.0",
              6 to family.replace(" ", "") + "-Regular")
      val strings = recs.map { it.second.toByteArray(Charsets.UTF_16BE) }
      return table {
        writeShort(0)
        writeShort(recs.size)
        writeShort(6 + recs.size * 12)
        var off = 0
        recs.forEachIndexed { i, (id, _) ->
          writeShort(3)
          writeShort(1)
          writeShort(0x409)
          writeShort(id)
          writeShort(strings[i].size)
          writeShort(off)
          off += strings[i].size
        }
        strings.forEach { write(it) }
      }
    }

    private fun checksum(b: ByteArray): Long {
      var sum = 0L
      var i = 0
      while (i < b.size) {
        var v = 0L
        for (k in 0 until 4) v = (v shl 8) or ((if (i + k < b.size) b[i + k].toLong() else 0L) and 0xFF)
        sum = (sum + v) and 0xFFFFFFFFL
        i += 4
      }
      return sum
    }

    private fun assemble(tables: Map<String, ByteArray>): ByteArray {
      val n = tables.size
      var pow = 1
      var log = 0
      while (pow * 2 <= n) {
        pow *= 2
        log++
      }
      val b = ByteArrayOutputStream()
      val d = DataOutputStream(b)
      d.writeInt(0x00010000)
      d.writeShort(n)
      d.writeShort(pow * 16)
      d.writeShort(log)
      d.writeShort(n * 16 - pow * 16)
      var off = 12 + n * 16
      val offsets = mutableMapOf<String, Int>()
      for ((tag, data) in tables) {
        d.writeBytes(tag)
        d.writeInt(checksum(data).toInt())
        d.writeInt(off)
        d.writeInt(data.size)
        offsets[tag] = off
        off += (data.size + 3) / 4 * 4
      }
      for ((_, data) in tables) {
        d.write(data)
        repeat((4 - data.size % 4) % 4) { d.writeByte(0) }
      }
      val font = b.toByteArray()
      val adj = (0xB1B0AFBAL - checksum(font)) and 0xFFFFFFFFL
      val at = offsets.getValue("head") + 8
      for (k in 0 until 4) font[at + k] = (adj shr (24 - 8 * k)).toByte()
      return font
    }
  }
}
