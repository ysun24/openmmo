package de.fiereu.openmmo.web.assets

import java.awt.image.BufferedImage
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * r5-contests: the berry tree sprites (pokeemerald src/data/object_events/berry_tree_graphics_tables.h).
 * Per berry one 9-frame sheet of 16x32 cells: frame 0 the dirt pile, 1-2 the sprout, 3-8 the tree,
 * each in the palette its growth stage uses (gBerryTreePaletteSlotTable_*: PALSLOT_NPC_1..4 =
 * npc_1..4.pal); the 16x16 frames sit at the cell's bottom. sAnim_BerryTreeStage0-4 give the
 * frames per stage. Manifest `contest.berryTrees` (PROTOCOL.md "r5-contests").
 */
class ContestAssetsExport(private val decomp: File, private val out: Output) {
  private val em = File(decomp, "pokeemerald")
  val failures = mutableListOf<String>()

  private fun text(p: String) = File(em, p).readText()

  fun export(): JsonObject {
    val tables = text("src/data/object_events/berry_tree_graphics_tables.h")
    val graphics = text("src/data/object_events/object_event_graphics.h")
    val pics =
        Regex("""const u32 (gObjectEventPic_\w+)\[\]\s*=\s*INCGFX_U32\("([^"]+)\.png"""")
            .findAll(graphics)
            .associate { it.groupValues[1] to it.groupValues[2] + ".png" }
    val palettes =
        (0..3).map { Decomp.readPalette(File(em, "graphics/object_events/palettes/npc_${it + 1}.pal")) }
    val picTables =
        Regex("""static const struct SpriteFrameImage (sPicTable_\w+)\[\]\s*=\s*\{(.*?)\};""", RegexOption.DOT_MATCHES_ALL)
            .findAll(tables)
            .associate { m ->
              m.groupValues[1] to
                  Regex("""overworld_frame\((\w+),\s*(\d+),\s*(\d+),\s*(\d+)\)""")
                      .findAll(m.groupValues[2])
                      .map { Triple(it.groupValues[1], it.groupValues[3].toInt(), it.groupValues[4].toInt()) }
                      .toList()
            }
    val palSlots =
        Regex("""const u8 (gBerryTreePaletteSlotTable_\w+)\[\]\s*=\s*\{([^}]*)\}""")
            .findAll(tables)
            .associate { m -> m.groupValues[1] to m.groupValues[2].split(",").map { it.trim().toInt() } }
    val berryOrder =
        Regex("""\[(ITEM_\w+) - FIRST_BERRY_INDEX\]\s*=\s*(sPicTable_\w+)""")
            .findAll(tables.substringAfter("gBerryTreePicTablePointers[]").substringBefore("};"))
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
    val palOrder =
        Regex("""\[(ITEM_\w+) - FIRST_BERRY_INDEX\]\s*=\s*(gBerryTreePaletteSlotTable_\w+)""")
            .findAll(tables.substringAfter("gBerryTreePaletteSlotTablePointers[]").substringBefore("};"))
            .associate { it.groupValues[1] to it.groupValues[2] }
    val sheets = buildJsonObject {
      berryOrder.forEachIndexed { i, (item, table) ->
        runCatching {
              val frames = picTables.getValue(table)
              val slots = palSlots.getValue(palOrder.getValue(item))
              val sheet = BufferedImage(16 * frames.size, 32, BufferedImage.TYPE_INT_ARGB)
              val g = sheet.createGraphics()
              frames.forEachIndexed { f, (pic, hTiles, frame) ->
                val stage = STAGE_OF_FRAME[f]
                val pal = palettes.getOrNull(slots[stage] - 2)
                val img = Decomp.readIndexed(File(em, pics.getValue(pic)), pal)
                val h = hTiles * 8
                val src = img.getSubimage(frame * 16, 0, 16, h)
                g.drawImage(src, f * 16, 32 - h, null)
              }
              g.dispose()
              put("${i + 1}", out.writePng("contest/berrytree/${i + 1}.png", sheet))
            }
            .onFailure { failures += "berry tree $item: $it" }
      }
    }
    return buildJsonObject {
      put("berryTrees", sheets)
      put("frameW", 16)
      put("frameH", 32)
      // sAnim_BerryTreeStage0..4: [frame, duration in GBA frames] per stage 1..5
      putJsonObject("stageAnims") {
        STAGE_ANIMS.forEachIndexed { s, frames ->
          put(
              "${s + 1}",
              buildJsonArray {
                for ((f, d) in frames)
                    add(
                        buildJsonArray {
                          add(kotlinx.serialization.json.JsonPrimitive(f))
                          add(kotlinx.serialization.json.JsonPrimitive(d))
                        })
              })
        }
      }
    }
  }

  companion object {
    /** Which growth stage (0-based) each of the nine frames belongs to. */
    private val STAGE_OF_FRAME = intArrayOf(0, 1, 1, 2, 2, 3, 3, 4, 4)
    private val STAGE_ANIMS =
        listOf(
            listOf(0 to 32),
            listOf(1 to 32, 2 to 32),
            listOf(3 to 48, 4 to 48),
            listOf(5 to 32, 5 to 32, 6 to 32, 6 to 32),
            listOf(7 to 48, 7 to 48, 8 to 48, 8 to 48))
  }
}
