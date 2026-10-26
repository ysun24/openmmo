package de.fiereu.openmmo.client.gfx

import java.awt.image.BufferedImage
import java.io.File

/**
 * One overworld sprite: the sheet and the logical frames the decomp's pic table lists. The standard
 * nine are 0 face down, 1 face up, 2 face left, 3-4 walk down, 5-6 walk up, 7-8 walk left; right is
 * left mirrored, as on the GBA.
 */
class ObjectSprite(
    val sheet: BufferedImage,
    val width: Int,
    val height: Int,
    /** Logical frame -> index of the frame inside [sheet]. */
    val frames: List<Int>,
) {
  /**
   * The sheet frame for a facing (Direction ordinal: 0 down, 1 up, 2 left, 3 right) and a walk
   * phase (0 standing, 1 and 2 the two stepping frames). Sprites without walk frames stand still.
   */
  fun frameFor(facingIndex: Int, walkPhase: Int): Int {
    val standing =
        when (facingIndex) {
          1 -> 1
          2,
          3 -> 2
          else -> 0
        }
    val walking = if (walkPhase == 0) standing else 3 + standing * 2 + (walkPhase - 1)
    val logical =
        when {
          walking < frames.size -> walking
          standing < frames.size -> standing
          else -> 0
        }
    return frames.getOrElse(logical) { 0 }
  }
}

/**
 * The decomp's object event graphics table, `OBJ_EVENT_GFX_*` id -> sprite, read from
 * `include/constants/event_objects.h` and `src/data/object_events/`. The server sends the same
 * constant values in NpcSpawn's graphicsId, because its map codegen reads the same header.
 */
class ObjectEventGraphics(private val root: File, private val game: GbaGame) {
  private val base = File(root, game.dir)
  private val dir = File(base, "src/data/object_events")
  private val cache = mutableMapOf<Int, ObjectSprite?>()

  private val gfxIds: Map<Int, String> by lazy {
    val out = mutableMapOf<Int, String>()
    Regex("""#define\s+(OBJ_EVENT_GFX_\w+)\s+(\d+)\b""")
        .findAll(File(base, "include/constants/event_objects.h").readText())
        .forEach { m ->
          val id = m.groupValues[2].toInt()
          if (m.groupValues[1] != "NUM_OBJ_EVENT_GFX") out.putIfAbsent(id, m.groupValues[1])
        }
    out
  }

  /** `OBJ_EVENT_GFX_X` -> `gObjectEventGraphicsInfo_X`. */
  private val pointers: Map<String, String> by lazy {
    Regex("""\[(OBJ_EVENT_GFX_\w+)\]\s*=\s*&(\w+)""")
        .findAll(File(dir, "object_event_graphics_info_pointers.h").readText())
        .associate { it.groupValues[1] to it.groupValues[2] }
  }

  /** Info symbol -> (width, height, pic table symbol). */
  private val infos: Map<String, Triple<Int, Int, String>> by lazy {
    val text = File(dir, "object_event_graphics_info.h").readText()
    Regex("""ObjectEventGraphicsInfo\s+(\w+)\s*=\s*\{(.*?)\};""", RegexOption.DOT_MATCHES_ALL)
        .findAll(text)
        .mapNotNull { m ->
          val body = m.groupValues[2]
          fun field(name: String) = Regex("""\.$name\s*=\s*(\w+)""").find(body)?.groupValues?.get(1)
          val w = field("width")?.toIntOrNull() ?: return@mapNotNull null
          val h = field("height")?.toIntOrNull() ?: return@mapNotNull null
          val images = field("images") ?: return@mapNotNull null
          m.groupValues[1] to Triple(w, h, images)
        }
        .toMap()
  }

  /** Pic table symbol -> list of (pic symbol, sheet frame index). */
  private val picTables: Map<String, List<Pair<String, Int>>> by lazy {
    val text = File(dir, "object_event_pic_tables.h").readText()
    Regex("""SpriteFrameImage\s+(\w+)\[\]\s*=\s*\{(.*?)\};""", RegexOption.DOT_MATCHES_ALL)
        .findAll(text)
        .associate { m ->
          val frames =
              Regex("""overworld_frame\((\w+),\s*\d+,\s*\d+,\s*(\d+)\)|obj_frame_tiles\((\w+)\)""")
                  .findAll(m.groupValues[2])
                  .map { f ->
                    if (f.groupValues[1].isNotEmpty()) f.groupValues[1] to f.groupValues[2].toInt()
                    else f.groupValues[3] to 0
                  }
                  .toList()
          m.groupValues[1] to frames
        }
  }

  /** Pic symbol -> png path. */
  private val pics: Map<String, String> by lazy {
    Regex("""(gObjectEventPic_\w+)\[\]\s*=\s*INC(?:BIN|GFX)_U(?:8|16|32)\("([^"]+)"""")
        .findAll(File(dir, "object_event_graphics.h").readText())
        .associate {
          it.groupValues[1] to it.groupValues[2].replace(Regex("""\.4bpp(\.lz)?$"""), ".png")
        }
  }

  fun nameOf(gfxId: Int): String? = gfxIds[gfxId]

  fun sprite(gfxId: Int): ObjectSprite? =
      cache.getOrPut(gfxId) {
        runCatching {
              val info =
                  infos[pointers[gfxIds[gfxId]] ?: return@getOrPut null] ?: return@getOrPut null
              val table = picTables[info.third].orEmpty()
              val pic = table.firstOrNull()?.first ?: return@getOrPut null
              val file = File(base, pics[pic] ?: return@getOrPut null)
              if (!file.exists()) return@getOrPut null
              ObjectSprite(
                  PretData.readSprite(file), info.first, info.second, table.map { it.second })
            }
            .getOrNull()
      }

  /** The player's own sprite: Red/Leaf in Kanto, Brendan/May in Hoenn. */
  fun playerSprite(female: Boolean): ObjectSprite? {
    val name =
        when (game) {
          GbaGame.FIRERED ->
              if (female) "OBJ_EVENT_GFX_GREEN_NORMAL" else "OBJ_EVENT_GFX_RED_NORMAL"
          GbaGame.EMERALD ->
              if (female) "OBJ_EVENT_GFX_MAY_NORMAL" else "OBJ_EVENT_GFX_BRENDAN_NORMAL"
        }
    val id = gfxIds.entries.firstOrNull { it.value == name }?.key ?: return null
    return sprite(id)
  }
}
