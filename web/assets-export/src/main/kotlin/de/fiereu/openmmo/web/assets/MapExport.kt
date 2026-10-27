package de.fiereu.openmmo.web.assets

import de.fiereu.openmmo.client.gfx.GbaGame
import de.fiereu.openmmo.client.gfx.LayoutData
import de.fiereu.openmmo.client.gfx.MetatilePainter
import de.fiereu.openmmo.client.gfx.MetatilePainter.Pixels
import de.fiereu.openmmo.client.gfx.PretData
import de.fiereu.openmmo.common.enums.MetatileBehaviorNames
import de.fiereu.openmmo.common.enums.TileBehavior
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One map of the server's map space, `region:bank:map` with its decomp layout. */
data class MapEntry(
    val region: Int,
    val bank: Int,
    val map: Int,
    val name: String,
    /** The layout constant, `LAYOUT_PALLET_TOWN`; maps sharing it share files. */
    val layoutId: String,
    val layout: LayoutData,
    val music: String?,
    /** (direction, offset, target key) for the four seamless edge connections. */
    val connections: List<Triple<String, Int, String>>,
    /** Warp tiles (x, y) from map.json `warp_events`: doors, stairs, arrows. */
    val warps: List<Pair<Int, Int>> = emptyList(),
    val allowCycling: Boolean = true,
    val allowRunning: Boolean = true,
    val requiresFlash: Boolean = false,
) {
  val key = "$region:$bank:$map"
}

/** The PNGs and collision grid of one layout; maps sharing a layout share these files. */
data class LayoutFiles(
    val bottom: String,
    val top: String,
    val collision: String,
    val border: String?,
    /** `<stem>.anim.json` when the layout has animated metatiles. */
    val anim: String? = null,
    /** `<stem>.beh.bin`: one TileBehavior ordinal per tile. */
    val behavior: String? = null,
    /** Per border cell, the anim.json metatile slot that animates it, or -1. */
    val borderAnim: List<Int>? = null,
)

class MapExport(private val decompRoot: File, private val out: Output) {
  private val json = Json { ignoreUnknownKeys = true }
  val failures: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
  private val anims = ConcurrentHashMap<GbaGame, TilesetAnims>()

  fun anims(game: GbaGame): TilesetAnims =
      anims.computeIfAbsent(game) { TilesetAnims(decompRoot, it) }

  val animFailures: List<String>
    get() = anims.values.flatMap { it.failures }

  /**
   * Every map of [game]. Banks and map numbers are positions in `map_groups.json`'s group_order,
   * shifted by the region's bank offset, which is what [PretData.mapName] and the server use.
   */
  fun collect(region: Int, game: GbaGame): List<MapEntry> {
    val pret = PretData(decompRoot, game)
    val base = File(decompRoot, game.dir)
    val entries = mutableListOf<MapEntry>()
    var bank = game.bankOffset
    while (pret.mapName(bank, 0) != null) {
      var map = 0
      while (true) {
        val name = pret.mapName(bank, map) ?: break
        val key = "$region:$bank:$map"
        try {
          val layout = pret.layout(bank, map)
          if (layout == null) {
            failures += "$key $name: layout not found"
          } else {
            val mapJson =
                json
                    .parseToJsonElement(File(base, "data/maps/$name/map.json").readText())
                    .jsonObject
            val music = mapJson["music"]?.jsonPrimitive?.contentOrNull
            val layoutId = mapJson["layout"]?.jsonPrimitive?.contentOrNull ?: name
            val conns =
                layout.connections.mapNotNull { c ->
                  if (c.direction !in DIRECTIONS) return@mapNotNull null
                  val addr = pret.addressOf(c.mapName)
                  if (addr == null) {
                    failures += "$key $name: connection target ${c.mapName} unknown"
                    return@mapNotNull null
                  }
                  Triple(c.direction, c.offset, "$region:${addr.first}:${addr.second}")
                }
            val warps =
                (mapJson["warp_events"] as? kotlinx.serialization.json.JsonArray)
                    .orEmpty()
                    .mapNotNull { w ->
                      val o = w as? JsonObject ?: return@mapNotNull null
                      val x =
                          o["x"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                              ?: return@mapNotNull null
                      val y =
                          o["y"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                              ?: return@mapNotNull null
                      x to y
                    }
            fun flag(k: String, d: Boolean) =
                mapJson[k]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: d
            entries +=
                MapEntry(
                    region,
                    bank,
                    map,
                    name,
                    layoutId,
                    layout,
                    music,
                    conns,
                    warps,
                    allowCycling = flag("allow_cycling", true),
                    allowRunning = flag("allow_running", true),
                    requiresFlash = flag("requires_flash", false))
          }
        } catch (e: Exception) {
          failures += "$key $name: ${e.javaClass.simpleName}: ${e.message}"
        }
        map++
      }
      bank++
    }
    return entries
  }

  /** Renders one layout: bottom/top PNGs, its border block image and the collision grid. */
  fun render(region: Int, game: GbaGame, layoutId: String, layout: LayoutData): LayoutFiles {
    val w = layout.width
    val h = layout.height
    val bottom = Pixels(w * 16, h * 16)
    val top = Pixels(w * 16, h * 16)
    for (y in 0 until h) for (x in 0 until w) {
      val id = layout.metatileAt(x, y) ?: continue
      MetatilePainter.drawMetatile(bottom, top, game, layout, id, x * 16, y * 16)
    }
    val stem = "maps/$region/${layoutId.removePrefix("LAYOUT_").lowercase()}"
    val anim =
        runCatching { anims(game).forLayout(layoutId, layout, "$stem.anim.png") }
            .onFailure { failures += "anim $region:$layoutId: $it" }
            .getOrNull()
    var animPath: String? = null
    if (anim != null) {
      // Animated top layers are drawn from the atlas every frame; the baked copy would show
      // through.
      for ((x, y) in anim.topCells) for (yy in 0 until 16) {
        java.util.Arrays.fill(
            top.data,
            (y * 16 + yy) * top.width + x * 16,
            (y * 16 + yy) * top.width + x * 16 + 16,
            0)
      }
      out.writePng("$stem.anim.png", anim.atlas.toImage())
      animPath =
          out.write(
              "$stem.anim.json",
              Json.encodeToString(JsonObject.serializer(), anim.json).toByteArray())
    }
    val b = out.writePng("$stem.bottom.png", bottom.toImage())
    val t = out.writePng("$stem.top.png", top.toImage())
    val c = out.write("$stem.col.bin", collision(layout))
    val beh = out.write("$stem.beh.bin", behaviors(game, layout))
    // Border cells drawn from the animation atlas (the sea around Pallet Town): the anim slot of
    // each border metatile, -1 when it does not animate.
    val borderAnim =
        anim?.let { a ->
          val ids =
              (a.json["metatiles"] as? kotlinx.serialization.json.JsonArray).orEmpty().map {
                (it as JsonObject)["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: -1
              }
          val slots = layout.border.map { ids.indexOf(it and 0x3FF) }
          slots.takeIf { s -> s.any { it >= 0 } }
        }
    val border =
        if (layout.border.size >= layout.borderWidth * layout.borderHeight &&
            layout.borderWidth > 0 &&
            layout.borderHeight > 0)
            out.writePng("$stem.border.png", border(game, layout).toImage())
        else null
    return LayoutFiles(b, t, c, border, animPath, beh, borderAnim)
  }

  private val behaviorIds = ConcurrentHashMap<GbaGame, Map<Int, TileBehavior>>()

  /**
   * One [TileBehavior] ordinal per tile (the server's own classification, MetatileBehaviorNames),
   * so the browser predicts ledges, water, bike terrain and field effects like the server.
   */
  fun behaviors(game: GbaGame, layout: LayoutData): ByteArray {
    val ids =
        behaviorIds.computeIfAbsent(game) {
          MetatileBehaviorNames.idTable(
              File(decompRoot, "${game.dir}/include/constants/metatile_behaviors.h"))
        }
    val width = if (game == GbaGame.FIRERED) 4 else 2
    return ByteArray(layout.width * layout.height) { i ->
      val id = layout.blocks.getOrElse(i) { 0 } and 0x3FF
      val attr =
          if (id < game.metatilesInPrimary) layout.primary.attributes.getOrElse(id) { 0 }
          else layout.secondary.attributes.getOrElse(id - game.metatilesInPrimary) { 0 }
      MetatileBehaviorNames.categorize(attr, width, ids).ordinal.toByte()
    }
  }

  fun manifestEntry(e: MapEntry, files: LayoutFiles): JsonObject = buildJsonObject {
    put("name", e.name)
    put("width", e.layout.width)
    put("height", e.layout.height)
    put("bottom", files.bottom)
    put("top", files.top)
    put("collision", files.collision)
    put("border", files.border?.let(::JsonPrimitive) ?: JsonNull)
    put("borderWidth", e.layout.borderWidth)
    put("borderHeight", e.layout.borderHeight)
    put(
        "connections",
        buildJsonArray {
          e.connections.forEach { (dir, off, target) ->
            add(
                buildJsonObject {
                  put("dir", dir)
                  put("offset", off)
                  put("map", target)
                })
          }
        })
    put("music", e.music?.let(::JsonPrimitive) ?: JsonNull)
    put(
        "warps",
        buildJsonArray {
          e.warps.forEach { (x, y) ->
            add(JsonPrimitive(x))
            add(JsonPrimitive(y))
          }
        })
    put("anim", files.anim?.let(::JsonPrimitive) ?: JsonNull)
    put("behavior", files.behavior?.let(::JsonPrimitive) ?: JsonNull)
    files.borderAnim?.let { slots ->
      put("borderAnim", buildJsonArray { slots.forEach { add(JsonPrimitive(it)) } })
    }
    // Map header rules the browser's prediction needs (r3-field).
    put("allowCycling", e.allowCycling)
    put("allowRunning", e.allowRunning)
    put("requiresFlash", e.requiresFlash)
  }

  companion object {
    val DIRECTIONS = setOf("up", "down", "left", "right")

    /**
     * The overworld field effect sprites the browser draws (r3-field): Emerald's art for both
     * regions (FireRed's are the same pictures in vertical strips). name -> (file, frame w, frame
     * h, palette under graphics/field_effects/palettes or null for the PNG's own).
     */
    private val FIELD_EFFECTS =
        listOf(
            FieldEffectSpec(
                "surfBlob", "surf_blob", 32, 32, "../../object_events/palettes/brendan"),
            FieldEffectSpec("tallGrass", "tall_grass", 16, 16, "general_1"),
            FieldEffectSpec("longGrass", "long_grass", 16, 16, "general_1"),
            FieldEffectSpec("shadow", "shadow_medium", 16, 8, null),
            FieldEffectSpec("footprints", "sand_footprints", 16, 16, "general_0"),
            FieldEffectSpec("deepFootprints", "deep_sand_footprints", 16, 16, "general_0"),
            FieldEffectSpec("tireTracks", "bike_tire_tracks", 16, 16, "general_0"),
            FieldEffectSpec("splash", "splash", 16, 8, "general_0"),
            FieldEffectSpec("ripple", "ripple", 16, 16, "general_1"),
            FieldEffectSpec("jumpSplash", "jump_small_splash", 16, 8, "general_0"),
            FieldEffectSpec("jumpGrass", "jump_tall_grass", 16, 8, "general_1"),
            FieldEffectSpec("dust", "ground_impact_dust", 16, 8, "general_0"),
            FieldEffectSpec("ashPuff", "ash_puff", 16, 16, "ash"),
            FieldEffectSpec("exclamation", "emotion_exclamation", 16, 16, null),
        )

    private data class FieldEffectSpec(
        val name: String,
        val file: String,
        val w: Int,
        val h: Int,
        val palette: String?,
    )

    /** Writes `fx/<name>.png` strips and returns the manifest's `fieldEffects` object. */
    fun fieldEffects(decompRoot: File, out: Output): JsonObject = buildJsonObject {
      val dir = File(decompRoot, "pokeemerald/graphics/field_effects")
      for (fx in FIELD_EFFECTS) {
        val png = File(dir, "pics/${fx.file}.png")
        if (!png.isFile) continue
        val pal = fx.palette?.let { Decomp.readPalette(File(dir, "palettes/$it.pal")) }
        val img = Decomp.readIndexed(png, pal)
        val count = maxOf(1, (img.width / fx.w) * (img.height / fx.h))
        val path = out.writePng("fx/${fx.file}.png", img)
        put(
            fx.name,
            buildJsonObject {
              put("sheet", path)
              put("frameW", fx.w)
              put("frameH", fx.h)
              put("count", count)
            })
      }
    }

    /** bit0 = blocked (collision bits != 0), bits 4-7 = elevation; one byte per tile. */
    fun collision(layout: LayoutData): ByteArray =
        ByteArray(layout.width * layout.height) { i ->
          val b = layout.blocks.getOrElse(i) { 0x0C00 }
          val blocked = if ((b shr 10) and 0x3 != 0) 1 else 0
          val elevation = (b shr 12) and 0xF
          (blocked or (elevation shl 4)).toByte()
        }

    /** The border blocks, both layers flattened, [LayoutData.borderWidth] x height metatiles. */
    fun border(game: GbaGame, layout: LayoutData): Pixels {
      val bw = layout.borderWidth
      val bh = layout.borderHeight
      val bottom = Pixels(bw * 16, bh * 16)
      val top = Pixels(bw * 16, bh * 16)
      for (y in 0 until bh) for (x in 0 until bw) {
        val id = layout.border[y * bw + x] and 0x3FF
        MetatilePainter.drawMetatile(bottom, top, game, layout, id, x * 16, y * 16)
      }
      for (i in bottom.data.indices) if (top.data[i] ushr 24 != 0) bottom.data[i] = top.data[i]
      return bottom
    }
  }
}
