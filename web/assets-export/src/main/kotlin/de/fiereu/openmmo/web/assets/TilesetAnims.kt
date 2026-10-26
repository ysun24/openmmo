package de.fiereu.openmmo.web.assets

import de.fiereu.openmmo.client.gfx.GbaGame
import de.fiereu.openmmo.client.gfx.LayoutData
import de.fiereu.openmmo.client.gfx.MetatilePainter
import de.fiereu.openmmo.client.gfx.MetatilePainter.Pixels
import de.fiereu.openmmo.client.gfx.PretData
import de.fiereu.openmmo.client.gfx.Tileset
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Tileset animations (water, flowers, fountains...) of one game, read from the decomp's
 * `src/tileset_anims.c` by running its callbacks ([MiniC]) for every value of the animation
 * counter.
 *
 * On the GBA, `UpdateTilesetAnimations` runs once per frame: it bumps the primary and the secondary
 * counter (each wrapping at its own `CounterMax`) and calls the tilesets' callbacks, which DMA a
 * frame of graphics over a range of background tiles. Every metatile that uses those tiles changes.
 */
class TilesetAnims(private val decompRoot: File, private val game: GbaGame) {
  private val base = File(decompRoot, game.dir)
  private val decomp = Decomp(base)
  val failures: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

  /** One DMA: at counter value [tick], frame symbol [src] goes to VRAM tiles [dest]..+[size]. */
  data class Copy(val tick: Int, val src: String, val dest: Int, val size: Int)

  /** A VRAM tile range and the frame it holds at each counter value. */
  class Channel(
      val dest: Int,
      val size: Int,
      /** Counter period (CounterMax, at least 1). */
      val cycle: Int,
      /** Frame symbols, indexed by [frameAt]. */
      val frames: List<String>,
      /** Frame index at each counter value 0 until [cycle] (state after that tick's copies). */
      val frameAt: IntArray,
      /** Change points (tick, frame), for the browser. */
      val events: List<Pair<Int, Int>>,
  )

  private class Init(val max: String?, val callback: String?)

  private val interp: MiniC? by lazy {
    val f = File(base, "src/tileset_anims.c")
    if (!f.isFile) return@lazy null
    val copies = ThreadLocal.withInitial { mutableListOf<Copy>() }
    lateinit var self: MiniC
    self =
        MiniC(
            f.readText(),
            mapOf(
                "NUM_TILES_IN_PRIMARY" to game.tilesInPrimary.toLong(),
                "BG_VRAM" to 0L,
                "TILE_SIZE_4BPP" to 1L,
                "NULL" to 0L,
                "TRUE" to 1L,
                "FALSE" to 0L,
                "TASK_NONE" to 0xFFL,
            )) { name, args ->
              when (name) {
                "TILE_OFFSET_4BPP" -> args[0]
                "AppendTilesetAnimToBuffer" -> {
                  val src = args[0] as? String
                  val dest = args[1] as? Long
                  val size = args[2] as? Long
                  if (src != null && dest != null && size != null)
                      copies.get() += Copy(currentTick.get(), src, dest.toInt(), size.toInt())
                  0L
                }
                else -> null
              }
            }
    recorder = copies
    self
  }
  @Volatile private var recorder: ThreadLocal<MutableList<Copy>>? = null
  private val currentTick = ThreadLocal.withInitial { 0 }

  private val inits: Map<String, Init> by lazy {
    val out = HashMap<String, Init>()
    val text =
        MiniC.stripComments(
            File(base, "src/tileset_anims.c").takeIf { it.isFile }?.readText() ?: "")
    Regex(
            """void\s+(InitTilesetAnim_\w+)\s*\(\s*void\s*\)\s*\{(.*?)\n}""",
            RegexOption.DOT_MATCHES_ALL)
        .findAll(text)
        .forEach { m ->
          val body = m.groupValues[2]
          val max =
              Regex("""s(?:Primary|Secondary)TilesetAnimCounterMax\s*=\s*(\w+)""")
                  .find(body)
                  ?.groupValues
                  ?.get(1)
          val cb =
              Regex("""s(?:Primary|Secondary)TilesetAnimCallback\s*=\s*(\w+)""")
                  .find(body)
                  ?.groupValues
                  ?.get(1)
                  ?.takeIf { it != "NULL" }
          out[m.groupValues[1]] = Init(max, cb)
        }
    out
  }

  /** `gTileset_X` -> its `.callback` (an `InitTilesetAnim_*`), when it has one. */
  private val tilesetInit: Map<String, String> by lazy {
    val text = File(base, "src/data/tilesets/headers.h").readText()
    Regex("""const struct Tileset (\w+)\s*=\s*\{(.*?)\};""", RegexOption.DOT_MATCHES_ALL)
        .findAll(text)
        .mapNotNull { m ->
          Regex("""\.callback\s*=\s*(\w+)""")
              .find(m.groupValues[2])
              ?.groupValues
              ?.get(1)
              ?.takeIf { it != "NULL" }
              ?.let { m.groupValues[1] to it }
        }
        .toMap()
  }

  /** `LAYOUT_X` -> (primary, secondary) tileset symbols. */
  private val layoutTilesets: Map<String, Pair<String, String>> by lazy {
    val json =
        kotlinx.serialization.json.Json.parseToJsonElement(
            File(base, "data/layouts/layouts.json").readText())
    json.jsonObject
        .getValue("layouts")
        .jsonArray
        .map { it.jsonObject }
        .filter { "id" in it }
        .associate {
          it.getValue("id").jsonPrimitive.content to
              (it.getValue("primary_tileset").jsonPrimitive.content to
                  it.getValue("secondary_tileset").jsonPrimitive.content)
        }
  }

  private val channelCache = ConcurrentHashMap<Pair<String, String>, List<Channel>>()
  private val frameCache = ConcurrentHashMap<String, List<IntArray>>()

  /** Every animated tile range of a layout with these tilesets. */
  fun channels(primary: String, secondary: String): List<Channel> =
      channelCache.getOrPut(primary to secondary) {
        val c = interp ?: return@getOrPut emptyList()
        val pInit = tilesetInit[primary]?.let { inits[it] }
        val sInit = tilesetInit[secondary]?.let { inits[it] }
        fun maxOf(init: Init?, primaryMax: Int): Int =
            when (val m = init?.max) {
              null -> 0
              "sPrimaryTilesetAnimCounterMax" -> primaryMax
              else -> runCatching { MiniC.parseNum(m).toInt() }.getOrDefault(0)
            }
        val pMax = maxOf(pInit, 0)
        val sMax = maxOf(sInit, pMax)
        run(c, pInit?.callback, pMax) + run(c, sInit?.callback, sMax)
      }

  private fun run(c: MiniC, callback: String?, max: Int): List<Channel> {
    callback ?: return emptyList()
    val cycle = max.coerceAtLeast(1)
    val rec = recorder!!
    val all = mutableListOf<Copy>()
    for (t in 0 until cycle) {
      rec.get().clear()
      currentTick.set(t)
      runCatching { c.call(callback, listOf(t.toLong())) }
          .onFailure { failures += "${game.dir} $callback($t): ${it.message}" }
      all += rec.get()
    }
    rec.get().clear()
    return all.groupBy { it.dest to it.size }
        .map { (k, copies) ->
          val frames = copies.map { it.src }.distinct()
          val at = IntArray(cycle) { -1 }
          copies.forEach { at[it.tick] = frames.indexOf(it.src) }
          // A tick without a copy keeps the previous frame (cyclically).
          val last = at.last { it >= 0 }
          var cur = last
          for (t in 0 until cycle) if (at[t] >= 0) cur = at[t] else at[t] = cur
          val events = mutableListOf<Pair<Int, Int>>()
          for (t in 0 until cycle) if (t == 0 || at[t] != at[t - 1]) events += t to at[t]
          Channel(k.first, k.second, cycle, frames, at, events)
        }
        .sortedBy { it.dest }
  }

  /** The 8x8 tiles of an animation frame symbol (4bpp colour indices). */
  private fun frameTiles(sym: String): List<IntArray> =
      frameCache.getOrPut(sym) {
        val path = decomp.symbols[sym]?.firstOrNull() ?: return@getOrPut emptyList()
        val f = File(base, path).takeIf { it.isFile } ?: return@getOrPut emptyList()
        runCatching { PretData.readTiles(f) }.getOrDefault(emptyList())
      }

  /** What a layout's animation export consists of. */
  class LayoutAnim(
      val atlas: Pixels,
      val json: JsonObject,
      /** Metatile cells (x, y) whose top layer is animated: blank them in the baked top image. */
      val topCells: List<Pair<Int, Int>>,
  )

  /**
   * Renders every animated metatile of [layout] in every combination of frames it shows over the
   * animation cycle: the atlas has one 16x32 cell per (metatile, combination): bottom layer above,
   * top layer below.
   */
  fun forLayout(layoutId: String, layout: LayoutData, atlasPath: String): LayoutAnim? {
    val (pSym, sSym) = layoutTilesets[layoutId] ?: return null
    val channels = channels(pSym, sSym)
    if (channels.isEmpty()) return null
    val tileChannel = IntArray(2048) { -1 }
    channels.forEachIndexed { ci, ch ->
      for (t in ch.dest until (ch.dest + ch.size).coerceAtMost(tileChannel.size)) tileChannel[t] =
          ci
    }
    // metatile id -> (channels it shows, top layer animated)
    val metas = LinkedHashMap<Int, Pair<List<Int>, Boolean>>()
    val positions = LinkedHashMap<Int, MutableList<Int>>()
    for (y in 0 until layout.height) for (x in 0 until layout.width) {
      val id = layout.metatileAt(x, y) ?: continue
      val info =
          metas.getOrPut(id) {
            val primary = id < game.metatilesInPrimary
            val set = if (primary) layout.primary else layout.secondary
            val refs = set.metatiles.getOrNull(if (primary) id else id - game.metatilesInPrimary)
            if (refs == null) return@getOrPut emptyList<Int>() to false
            val covered =
                MetatilePainter.layerType(game, layout, id) == MetatilePainter.LAYER_COVERED
            val chs = sortedSetOf<Int>()
            var top = false
            refs.forEachIndexed { i, r ->
              val c = tileChannel.getOrElse(r.tile) { -1 }
              if (c >= 0) {
                chs += c
                if (i >= 4 && !covered) top = true
              }
            }
            chs.toList() to top
          }
      if (info.first.isNotEmpty())
          positions
              .getOrPut(id) { mutableListOf() }
              .apply {
                add(x)
                add(y)
              }
    }
    if (positions.isEmpty()) return null

    // Combined timeline: both counters run together from the map load.
    val cycles = channels.map { it.cycle }.distinct()
    val span = cycles.fold(1L) { a, b -> lcm(a, b.toLong()) }.coerceAtMost(20_000L).toInt()
    data class Cell(val id: Int, val combo: List<Int>)
    val cells = mutableListOf<Cell>()
    val metaJson = buildJsonArray {
      for ((id, pos) in positions) {
        val (chs, top) = metas.getValue(id)
        val combos = LinkedHashSet<List<Int>>()
        for (t in 0 until span) {
          combos += chs.map { channels[it].frameAt[t % channels[it].cycle] }
          if (combos.size >= 96) break
        }
        add(
            buildJsonObject {
              put("id", id)
              putJsonArray("ch") { chs.forEach { add(it) } }
              put("top", top)
              putJsonArray("combos") {
                combos.forEach { combo ->
                  add(
                      buildJsonArray {
                        combo.forEach { add(it) }
                        add(cells.size)
                      })
                  cells += Cell(id, combo)
                }
              }
              putJsonArray("cells") { pos.forEach { add(it) } }
            })
      }
    }
    val cols = 16
    val rows = (cells.size + cols - 1) / cols
    val atlas = Pixels(cols * 16, rows * 32)
    val scratchBottom = Pixels(16, 16)
    val scratchTop = Pixels(16, 16)
    cells.forEachIndexed { n, cell ->
      val chs = metas.getValue(cell.id).first
      val patched = patch(layout, chs.mapIndexed { k, ci -> channels[ci] to cell.combo[k] })
      scratchBottom.data.fill(0)
      scratchTop.data.fill(0)
      MetatilePainter.drawMetatile(scratchBottom, scratchTop, game, patched, cell.id, 0, 0)
      val ox = (n % cols) * 16
      val oy = (n / cols) * 32
      for (yy in 0 until 16) for (xx in 0 until 16) {
        atlas.data[(oy + yy) * atlas.width + ox + xx] = scratchBottom.data[yy * 16 + xx]
        atlas.data[(oy + 16 + yy) * atlas.width + ox + xx] = scratchTop.data[yy * 16 + xx]
      }
    }
    val json = buildJsonObject {
      put("image", atlasPath)
      put("cols", cols)
      putJsonArray("channels") {
        channels.forEach { ch ->
          add(
              buildJsonObject {
                put("dest", ch.dest)
                put("size", ch.size)
                put("cycle", ch.cycle)
                putJsonArray("events") {
                  ch.events.forEach { (t, f) ->
                    add(t)
                    add(f)
                  }
                }
              })
        }
      }
      put("metatiles", metaJson)
    }
    val topCells = mutableListOf<Pair<Int, Int>>()
    for ((id, pos) in positions) if (metas.getValue(id).second) {
      for (k in pos.indices step 2) topCells += pos[k] to pos[k + 1]
    }
    return LayoutAnim(atlas, json, topCells)
  }

  /** [layout] with each channel's tiles replaced by the given frame of it. */
  private fun patch(layout: LayoutData, frames: List<Pair<Channel, Int>>): LayoutData {
    val p = layout.primary.tiles.toMutableList()
    val s = layout.secondary.tiles.toMutableList()
    for ((ch, f) in frames) {
      val tiles = frameTiles(ch.frames.getOrNull(f) ?: continue)
      for (k in 0 until minOf(ch.size, tiles.size)) {
        val t = ch.dest + k
        if (t < game.tilesInPrimary) put(p, t, tiles[k])
        else put(s, t - game.tilesInPrimary, tiles[k])
      }
    }
    fun ts(o: Tileset, tiles: List<IntArray>) =
        Tileset(tiles, o.palettes, o.metatiles, o.attributes)
    return LayoutData(
        layout.name,
        layout.width,
        layout.height,
        layout.blocks,
        ts(layout.primary, p),
        ts(layout.secondary, s),
        layout.border,
        layout.borderWidth,
        layout.borderHeight,
        layout.connections)
  }

  private fun put(list: MutableList<IntArray>, i: Int, tile: IntArray) {
    while (list.size <= i) list += IntArray(64)
    list[i] = tile
  }

  companion object {
    fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    fun lcm(a: Long, b: Long): Long = a / gcd(a, b) * b
  }
}
