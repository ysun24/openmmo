package de.fiereu.openmmo.web.assets

import de.fiereu.openmmo.client.gfx.GbaGame
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ExportTest {
  private val decomp = File(System.getProperty("decomp.root") ?: "../../decomp")

  @Test
  fun palletTownLayoutConnectionsAndCollision() {
    val maps = MapExport(decomp, Output(createTempDir()))
    val pallet = maps.collect(0, GbaGame.FIRERED).first { it.key == "0:3:0" }
    assertEquals("PalletTown", pallet.name)
    assertEquals(24, pallet.layout.width)
    assertEquals(20, pallet.layout.height)
    assertTrue(Triple("up", 0, "0:3:19") in pallet.connections, "${pallet.connections}")
    val col = MapExport.collision(pallet.layout)
    fun at(x: Int, y: Int) = col[y * 24 + x].toInt()
    // A tree in the top-left corner and the wall of the player's house are blocked...
    assertEquals(1, at(0, 0) and 1)
    assertEquals(1, at(6, 6) and 1)
    // ...the road in front of the house is not, at the normal ground elevation 3.
    assertEquals(0, at(8, 10) and 1)
    assertEquals(3, at(8, 10) shr 4)
  }

  @Test
  fun fullExportManifest() {
    val dir = createTempDir()
    val result = Exporter(decomp, dir, 4).run()
    assertEquals(emptyList(), result.failures)
    val m = Json.parseToJsonElement(File(dir, "manifest.json").readText()).jsonObject
    val pallet = m.getValue("maps").jsonObject.getValue("0:3:0").jsonObject
    assertEquals(24, pallet.getValue("width").jsonPrimitive.int)
    val route1 = pallet.getValue("connections").jsonArray.first().jsonObject
    assertEquals("0:3:19", route1.getValue("map").jsonPrimitive.content)
    assertEquals(
        "Route1",
        m.getValue("maps")
            .jsonObject
            .getValue("0:3:19")
            .jsonObject
            .getValue("name")
            .jsonPrimitive
            .content)
    // Hoenn banks are shifted by 50, as the server numbers them.
    assertEquals(
        "LittlerootTown",
        m.getValue("maps")
            .jsonObject
            .getValue("1:50:9")
            .jsonObject
            .getValue("name")
            .jsonPrimitive
            .content)
    val bottom = ImageIO.read(File(dir, pallet.getValue("bottom").jsonPrimitive.content))
    assertEquals(24 * 16, bottom.width)
    assertEquals(20 * 16, bottom.height)
    assertEquals(24 * 20L, File(dir, pallet.getValue("collision").jsonPrimitive.content).length())
    val bulbasaur = m.getValue("pokemon").jsonObject.getValue("1").jsonObject
    val front = ImageIO.read(File(dir, bulbasaur.getValue("front").jsonPrimitive.content))
    assertEquals(64, front.width)
    assertEquals(0, front.getRGB(0, 0) ushr 24, "colour 0 is transparent")
    assertEquals(386, m.getValue("pokemon").jsonObject.size)
    val players = m.getValue("players").jsonObject
    assertNotNull(players["0"]?.jsonObject?.get("male"))
    assertNotNull(players["1"]?.jsonObject?.get("female"))
    val red =
        m.getValue("objects").jsonObject.values.first {
          it.jsonObject.getValue("name").jsonPrimitive.content == "OBJ_EVENT_GFX_RED_NORMAL"
        }
    assertEquals(32, red.jsonObject.getValue("frameH").jsonPrimitive.int)
    // A second run writes the same bytes.
    val before = File(dir, "manifest.json").readBytes()
    Exporter(decomp, dir, 4).run()
    assertTrue(before.contentEquals(File(dir, "manifest.json").readBytes()))

    // Tileset animations: Pallet Town's flowers and water (FireRed's general tileset).
    val anim =
        Json.parseToJsonElement(File(dir, pallet.getValue("anim").jsonPrimitive.content).readText())
            .jsonObject
    val channels = anim.getValue("channels").jsonArray.map { it.jsonObject }
    fun ch(dest: Int) = channels.first { it.getValue("dest").jsonPrimitive.int == dest }
    assertEquals(48, ch(416).getValue("size").jsonPrimitive.int, "water + land water edge")
    assertEquals(640, ch(416).getValue("cycle").jsonPrimitive.int)
    // TilesetAnim_General: water copies on timer % 16 == 1, frame (timer / 16) % 8
    val ev = ch(416).getValue("events").jsonArray.map { it.jsonPrimitive.int }
    assertEquals(listOf(0, 7, 1, 0, 17, 1, 33, 2), ev.take(8))
    assertEquals(4, ch(508).getValue("size").jsonPrimitive.int, "flowers")
    val metas = anim.getValue("metatiles").jsonArray.map { it.jsonObject }
    assertTrue(metas.any { it.getValue("combos").jsonArray.size == 8 }, "8 water frames")
    assertTrue(File(dir, anim.getValue("image").jsonPrimitive.content).isFile)
    assertNotNull(
        m.getValue("maps").jsonObject.getValue("1:50:9").jsonObject["anim"]?.jsonPrimitive)
    // warps
    val house = m.getValue("maps").jsonObject.getValue("0:4:1").jsonObject
    assertEquals(listOf(10, 2), house.getValue("warps").jsonArray.map { it.jsonPrimitive.int })

    // Music: the decomp MIDIs + voice tables
    assertEquals(
        "music/0/index.json", m.getValue("music").jsonObject.getValue("0").jsonPrimitive.content)
    val idx = Json.parseToJsonElement(File(dir, "music/0/index.json").readText()).jsonObject
    val song = idx.getValue("songs").jsonObject.getValue("MUS_PALLET").jsonObject
    assertEquals(100, song.getValue("vol").jsonPrimitive.int)
    assertTrue(
        File(dir, song.getValue("mid").jsonPrimitive.content).readBytes().take(4) ==
            "MThd".toByteArray().toList())
    val sq = song.getValue("voices").jsonObject.getValue("80").jsonObject
    assertEquals("sq", sq.getValue("t").jsonPrimitive.content)
    assertEquals(
        "MUS_VS_WILD",
        m.getValue("battleMusic")
            .jsonObject
            .getValue("1")
            .jsonObject
            .getValue("wild")
            .jsonPrimitive
            .content)
    val emerald = Json.parseToJsonElement(File(dir, "music/1/index.json").readText()).jsonObject
    val drums =
        emerald
            .getValue("songs")
            .jsonObject
            .getValue("MUS_LITTLEROOT")
            .jsonObject
            .getValue("voices")
            .jsonObject
            .getValue("0")
            .jsonObject
    assertTrue(
        drums.getValue("keys").jsonObject.values.all { it.jsonObject["drum"] != null }, "drum kit")
    dir.deleteRecursively()
  }

  @Test
  fun miniCInterpretsCounters() {
    val src =
        """
        static const u16 *const sFrames[] = { gA, gB, gA };
        static void Q(u16 timer_div, u8 timer_mod)
        {
            timer_div -= timer_mod;
            if (timer_div < 2) { Append(sFrames[timer_div % ARRAY_COUNT(sFrames)], (u16 *)(BASE + 3)); }
            else Append(gC, 7);
        }
        """
            .trimIndent()
    val calls = mutableListOf<List<Any?>>()
    val c =
        MiniC(src, mapOf("BASE" to 10L)) { name, args ->
          if (name == "Append") {
            calls += args
            0L
          } else null
        }
    c.call("Q", listOf(1L, 0L))
    c.call("Q", listOf(0L, 1L)) // u16 wraps: 65535 >= 2
    assertEquals(listOf(listOf<Any?>("gB", 13L), listOf<Any?>("gC", 7L)), calls)
  }

  private fun createTempDir(): File = kotlin.io.path.createTempDirectory("webassets").toFile()
}
