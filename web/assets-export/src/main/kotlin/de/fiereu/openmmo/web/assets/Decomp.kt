package de.fiereu.openmmo.web.assets

import de.fiereu.openmmo.client.gfx.PretData
import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Generic readers for a pret decomp's C sources: which file each graphics symbol is built from,
 * `#define` tables, and indexed sprite PNGs. Understands both the classic `INCBIN_Uxx("x.4bpp.lz")`
 * and the newer `INCGFX_Uxx("x.png", ".4bpp.lz", ...)` forms, which pokeemerald now uses.
 */
class Decomp(val base: File) {
  /** Symbol -> source files (PNG / JASC palette / bin) its initialiser pulls in, in order. */
  val symbols: Map<String, List<String>> by lazy {
    val out = HashMap<String, List<String>>()
    val decl =
        Regex("""const\s+(?:u8|u16|u32)\s+(\w+)\s*\[[^\]]*\](?:\s*\[[^\]]*\])?\s*=\s*([^;]*);""")
    val inc = Regex("""INC(?:BIN|GFX)_U(?:8|16|32)\(\s*"([^"]+)"""")
    File(base, "src")
        .walkTopDown()
        .filter { it.isFile && (it.extension == "c" || it.extension == "h") }
        .sortedBy { it.path }
        .forEach { f ->
          val text = f.readText()
          if (!text.contains("INCBIN") && !text.contains("INCGFX")) return@forEach
          decl.findAll(text).forEach { m ->
            val paths = inc.findAll(m.groupValues[2]).map { normalize(it.groupValues[1]) }.toList()
            if (paths.isNotEmpty()) out.putIfAbsent(m.groupValues[1], paths)
          }
        }
    out
  }

  /** The decomp file a build artefact path is made from: `x.4bpp.lz` -> `x.png` and so on. */
  fun normalize(path: String): String =
      path
          .removeSuffix(".lz")
          .replace(Regex("""\.(4bpp|8bpp|1bpp)$"""), ".png")
          .replace(Regex("""\.gbapal$"""), ".pal")

  /**
   * The file for [path], or a form subfolder's copy when the decomp builds it by concatenating
   * forms (Castform's `front.4bpp` is made from `normal/front.png`, `sunny/front.png`...).
   */
  fun file(path: String): File? {
    val f = File(base, path)
    if (f.isFile) return f
    val alt = File(f.parentFile, "normal/${f.name}")
    return alt.takeIf { it.isFile }
  }

  fun text(path: String) = File(base, path).readText()

  /** `#define PREFIXname value` with a plain integer (decimal or hex) value. */
  fun defines(path: String, prefix: String): Map<String, Int> {
    val out = LinkedHashMap<String, Int>()
    Regex("""(?m)^\s*#define\s+($prefix\w+)\s+(0x[0-9A-Fa-f]+|\d+)\b""")
        .findAll(text(path))
        .forEach {
          val v = it.groupValues[2]
          out.putIfAbsent(
              it.groupValues[1], if (v.startsWith("0x")) v.substring(2).toInt(16) else v.toInt())
        }
    return out
  }

  companion object {
    /**
     * An indexed sprite PNG to ARGB: colours from [palette] (16 ARGB, a JASC `.pal`) or else the
     * PNG's own palette, colour index 0 transparent.
     */
    fun readIndexed(png: File, palette: IntArray?): BufferedImage {
      val img = ImageIO.read(png)
      val cm = img.colorModel as? IndexColorModel
      val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
      val px = IntArray(4)
      for (y in 0 until img.height) for (x in 0 until img.width) {
        if (cm == null) {
          out.setRGB(x, y, img.getRGB(x, y))
          continue
        }
        val idx = img.raster.getPixel(x, y, px)[0]
        if (idx and 0xF == 0) continue
        val c = palette?.getOrNull(idx and 0xF) ?: (cm.getRGB(idx) or (0xFF shl 24))
        out.setRGB(x, y, c)
      }
      return out
    }

    fun readPalette(file: File?): IntArray? =
        file?.takeIf { it.isFile }?.let { runCatching { PretData.readJascPalette(it) }.getOrNull() }

    /** True when the image has no opaque pixel. */
    fun isEmpty(img: BufferedImage): Boolean {
      for (y in 0 until img.height) for (x in 0 until img.width) {
        if (img.getRGB(x, y) ushr 24 != 0) return false
      }
      return true
    }

    fun png(img: BufferedImage): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
  }
}

/**
 * Writes the export, leaving byte-identical files untouched so reruns keep mtimes and caches warm,
 * and remembers what it wrote so stale files from an earlier run can be removed.
 */
class Output(val root: File) {
  private val written = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
  val bytes = java.util.concurrent.atomic.AtomicLong()

  fun write(rel: String, data: ByteArray): String {
    val f = File(root, rel)
    written += rel
    bytes.addAndGet(data.size.toLong())
    if (f.isFile && f.length() == data.size.toLong() && f.readBytes().contentEquals(data))
        return rel
    f.parentFile.mkdirs()
    val tmp = File(f.parentFile, ".${f.name}.tmp")
    tmp.writeBytes(data)
    if (!tmp.renameTo(f)) {
      f.delete()
      tmp.renameTo(f)
    }
    return rel
  }

  fun writePng(rel: String, img: BufferedImage) = write(rel, Decomp.png(img))

  /** Deletes files under [root] this run did not write; returns how many. */
  fun prune(): Int {
    var n = 0
    root
        .walkBottomUp()
        .filter { it.isFile }
        .forEach {
          val rel = it.relativeTo(root).invariantSeparatorsPath
          if (rel !in written && it.delete()) n++
        }
    root.walkBottomUp().filter { it.isDirectory && it != root }.forEach { it.delete() }
    return n
  }
}
