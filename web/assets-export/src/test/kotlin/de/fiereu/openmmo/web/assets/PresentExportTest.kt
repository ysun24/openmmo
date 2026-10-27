package de.fiereu.openmmo.web.assets

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * r3-present exports: fonts, window frames, shiny / icon sprites, sample profiles, sound effects.
 */
class PresentExportTest {
  private val decomp = File(System.getProperty("decomp.root") ?: "../../decomp")

  private fun tempDir(): File = kotlin.io.path.createTempDirectory("webpresent").toFile()

  @Test
  fun fontsAtlasWidthsAndTrueType() {
    val dir = tempDir()
    val fonts = FontExport(decomp, Output(dir)).export()
    assertEquals(4, fonts.size, "$fonts")
    val normal = fonts.getValue("pokeemerald/normal").jsonObject
    val meta =
        Json.parseToJsonElement(File(dir, normal.getValue("json").jsonPrimitive.content).readText())
            .jsonObject
    val glyphs = meta.getValue("glyphs").jsonObject
    // 'A' is byte 0xBB, 'é' 0x1B (POKéMON), the Pokedollar sign shares the yen glyph 0xB7
    assertEquals(0xBB, glyphs.getValue("${'A'.code}").jsonArray[0].jsonPrimitive.int)
    assertEquals(0x1B, glyphs.getValue("${'é'.code}").jsonArray[0].jsonPrimitive.int)
    assertEquals(0xB7, glyphs.getValue("${'₽'.code}").jsonArray[0].jsonPrimitive.int)
    // PK / MN at U+E053 / U+E054; no kana (they fall back to the system font)
    assertNotNull(glyphs["${0xE053}"])
    assertTrue(glyphs["${'あ'.code}"] == null)
    // widths from gFontNormalLatinGlyphWidths: space 6? 'A' 6, 'i' narrower than 'm'
    val w = { c: Char -> glyphs.getValue("${c.code}").jsonArray[1].jsonPrimitive.int }
    assertTrue(w('i') < w('m'), "proportional")
    // atlas: glyph pixels red, shadow green
    val atlas = ImageIO.read(File(dir, normal.getValue("image").jsonPrimitive.content))
    var red = 0
    var green = 0
    for (y in 0 until 16) for (x in 0 until 16) {
      val c = atlas.getRGB((0xBB % 16) * 16 + x, (0xBB / 16) * 16 + y)
      if (c == 0xFFFF0000.toInt()) red++
      if (c == 0xFF00FF00.toInt()) green++
    }
    assertTrue(red > 15 && green > 10, "A has glyph and shadow pixels ($red, $green)")
    // the TrueType is structurally sound: tables, checksum adjustment, glyph count
    val ttf = File(dir, normal.getValue("ttf").jsonPrimitive.content).readBytes()
    val d = DataInputStream(ByteArrayInputStream(ttf))
    assertEquals(0x00010000, d.readInt())
    val n = d.readShort().toInt()
    d.skip(6)
    val tags =
        (0 until n).map { String(ByteArray(4).also { b -> d.readFully(b) }).also { d.skip(12) } }
    assertEquals(
        listOf("OS/2", "cmap", "glyf", "head", "hhea", "hmtx", "loca", "maxp", "name", "post"),
        tags)
    var sum = 0L
    for (i in ttf.indices step 4) {
      var v = 0L
      for (k in 0 until 4) v = (v shl 8) or ((ttf.getOrElse(i + k) { 0 }.toLong()) and 0xFF)
      sum = (sum + v) and 0xFFFFFFFFL
    }
    assertEquals(0xB1B0AFBAL, sum, "whole-font checksum")
    dir.deleteRecursively()
  }

  @Test
  fun windowFramesAndArrow() {
    val dir = tempDir()
    val w = UiExport(decomp, Output(dir)).export()
    val fr = w.getValue("0").jsonObject
    val em = w.getValue("1").jsonObject
    assertEquals(10, fr.getValue("frames").jsonArray.size)
    assertEquals(20, em.getValue("frames").jsonArray.size)
    val frame = ImageIO.read(File(dir, em.getValue("frames").jsonArray[0].jsonPrimitive.content))
    assertEquals(24, frame.width)
    assertEquals(0xFFFFFFFF.toInt(), frame.getRGB(12, 12), "window fill is white")
    assertEquals(0, frame.getRGB(0, 0) ushr 24, "outer corner transparent")
    val dlg = ImageIO.read(File(dir, fr.getValue("dialog").jsonPrimitive.content))
    assertEquals(40, dlg.width)
    assertEquals(48, dlg.height)
    assertNotNull(em["arrow"])
    assertEquals(4, em.getValue("arrowSteps").jsonArray.size)
    dir.deleteRecursively()
  }

  @Test
  fun shinyAndTwoFrameIcons() {
    val ex = PokemonExport(decomp, "pokefirered")
    val img = ex.images(6)!!
    val shiny = assertNotNull(img.shinyFront)
    val front = img.front!!
    var differ = 0
    for (y in 0 until 64) for (x in 0 until 64) if (shiny.getRGB(x, y) != front.getRGB(x, y))
        differ++
    assertTrue(differ > 200, "shiny Charizard is recoloured ($differ px)")
    // same shape: transparency identical
    for (y in 0 until 64) for (x in 0 until 64) assertEquals(
        front.getRGB(x, y) ushr 24, shiny.getRGB(x, y) ushr 24)
    val icon = assertNotNull(img.iconAnim)
    assertEquals(32, icon.width)
    assertEquals(64, icon.height)
  }

  @Test
  fun sampleProfilesAndSoundEffects() {
    val dir = tempDir()
    val m = MusicExport(decomp, de.fiereu.openmmo.client.gfx.GbaGame.EMERALD, Output(dir))
    val path = assertNotNull(m.export(1))
    val idx = Json.parseToJsonElement(File(dir, path).readText()).jsonObject
    val samples = idx.getValue("samples").jsonObject
    // the piano sample sounds C4 at note 60 (261.6 Hz) and has a loop and harmonics
    val piano = samples.getValue("DirectSoundWaveData_sc88pro_piano1_60").jsonObject
    assertTrue(piano.getValue("loop").jsonPrimitive.boolean)
    val f = piano.getValue("pitch60").jsonPrimitive.double
    assertTrue(f in 255.0..268.0, "piano pitch60 $f")
    assertEquals(SampleProfile.HARMONICS, piano.getValue("re").jsonArray.size)
    // a kick drum has no clean pitch: band energies instead of harmonics
    val kick = samples.getValue("DirectSoundWaveData_sc88pro_rnd_kick").jsonObject
    assertNotNull(kick["bands"])
    // SE songs are exported, with their reverb
    val songs = idx.getValue("songs").jsonObject
    val select = songs.getValue("SE_SELECT").jsonObject
    assertEquals(50, select.getValue("rev").jsonPrimitive.int)
    assertNotNull(songs["MUS_LEVEL_UP"])
    // DirectSound voices point at their profile
    val littleroot = songs.getValue("MUS_LITTLEROOT").jsonObject.getValue("voices").jsonObject
    val anySample =
        littleroot.values.any { v ->
          val o = v.jsonObject
          o["sample"] != null ||
              (o["keys"]?.jsonObject?.values?.any { it.jsonObject["sample"] != null } ?: false)
        }
    assertTrue(anySample)
    assertNotEquals(0, SampleProfile.BANDS.size)
    dir.deleteRecursively()
  }
}
