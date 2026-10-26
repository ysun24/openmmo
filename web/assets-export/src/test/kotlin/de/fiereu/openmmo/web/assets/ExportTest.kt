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
    dir.deleteRecursively()
  }

  private fun createTempDir(): File = kotlin.io.path.createTempDirectory("webassets").toFile()
}
