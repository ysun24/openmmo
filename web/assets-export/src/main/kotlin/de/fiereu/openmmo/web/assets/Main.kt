package de.fiereu.openmmo.web.assets

import de.fiereu.openmmo.client.gfx.GbaGame
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Region id as the server uses it -> the decomp it is built from. */
val REGIONS = linkedMapOf(0 to GbaGame.FIRERED, 1 to GbaGame.EMERALD)

/**
 * Exports the web client's static assets from the pret decomps (docs/web/PROTOCOL.md section 4):
 *
 *     ./gradlew :web.assets:run --args="--decomp decomp --out web/static/assets"
 *
 * Output is deterministic; files whose bytes did not change are left alone, files an earlier run
 * wrote that this one did not are deleted.
 */
fun main(args: Array<String>) {
  var decomp = File("decomp")
  var outDir = File("web/static/assets")
  var threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 16)
  var i = 0
  while (i < args.size) {
    when (args[i]) {
      "--decomp" -> decomp = File(args[++i])
      "--out" -> outDir = File(args[++i])
      "--threads" -> threads = args[++i].toInt()
      "-h",
      "--help" -> {
        println("usage: --decomp <dir> --out <dir> [--threads N]")
        return
      }
      else -> {
        System.err.println("unknown argument ${args[i]}")
        exitProcess(2)
      }
    }
    i++
  }
  val result = Exporter(decomp, outDir, threads).run()
  println(result.summary())
}

class Exporter(private val decomp: File, private val outDir: File, private val threads: Int) {
  data class Result(
      val maps: Int,
      val layouts: Int,
      val objects: Int,
      val pokemon: Int,
      val failures: List<String>,
      val bytes: Long,
      val pruned: Int,
      val millis: Long,
  ) {
    fun summary() = buildString {
      appendLine("maps=$maps layouts=$layouts objects=$objects pokemon=$pokemon")
      appendLine("bytes=$bytes (${bytes / 1024 / 1024} MiB) pruned=$pruned time=${millis}ms")
      appendLine("failures=${failures.size}")
      failures.forEach { appendLine("  $it") }
    }
  }

  fun run(): Result {
    val start = System.currentTimeMillis()
    val out = Output(outDir)
    val pool = Executors.newFixedThreadPool(threads)
    try {
      val maps = MapExport(decomp, out)
      val battleAssets = BattleAssetsExport(decomp, out)
      val entries =
          REGIONS.map { (r, g) -> pool.submit(Callable { maps.collect(r, g) }) }.map { it.get() }

      // One render per layout; maps that share a layout (every Pokemon Center...) share files.
      val layoutJobs =
          entries
              .flatten()
              .distinctBy { it.region to it.layoutId }
              .map { e ->
                (e.region to e.layoutId) to
                    pool.submit(
                        Callable {
                          runCatching {
                                maps.render(
                                    e.region, REGIONS.getValue(e.region), e.layoutId, e.layout)
                              }
                              .onFailure {
                                maps.failures += "layout ${e.region}:${e.layoutId}: $it"
                              }
                              .getOrNull()
                        })
              }
      val objectJobs =
          REGIONS.map { (r, g) ->
            pool.submit(
                Callable {
                  val o = ObjectExport(decomp, g, out)
                  Triple(r, o, o.exportAll(r))
                })
          }
      val pokemon = listOf("pokefirered", "pokeemerald").map { PokemonExport(decomp, it) }
      val dexes = pokemon.flatMap { it.species.keys }.toSortedSet()
      val monJobs =
          dexes.map { dex -> dex to pool.submit(Callable { exportMon(out, pokemon, dex) }) }

      val musicJobs =
          REGIONS.map { (r, g) ->
            pool.submit(
                Callable {
                  val m = MusicExport(decomp, g, out)
                  Triple(
                      r,
                      m,
                      runCatching { m.export(r) }
                          .onFailure { m.failures += "music $r: $it" }
                          .getOrNull())
                })
          }
      val layoutFiles = layoutJobs.associate { (k, f) -> k to f.get() }
      val objects = objectJobs.map { it.get() }
      val mons = monJobs.mapNotNull { (dex, f) -> f.get()?.let { dex to it } }

      val manifest = buildJsonObject {
        put("version", 1)
        // r3-field: overworld field effect sprites.
        put("fieldEffects", MapExport.fieldEffects(decomp, out))
        // r3-field: names of the `<map>.behavior` grid values (TileBehavior ordinals).
        put(
            "behaviors",
            kotlinx.serialization.json.buildJsonArray {
              de.fiereu.openmmo.common.enums.TileBehavior.entries.forEach {
                add(kotlinx.serialization.json.JsonPrimitive(it.name))
              }
            })
        put(
            "maps",
            buildJsonObject {
              entries
                  .flatten()
                  .sortedWith(compareBy({ it.region }, { it.bank }, { it.map }))
                  .forEach { e ->
                    val files = layoutFiles[e.region to e.layoutId] ?: return@forEach
                    put(e.key, maps.manifestEntry(e, files))
                  }
            })
        put(
            "objects",
            buildJsonObject {
              objects.forEach { (r, _, sprites) ->
                sprites.forEach { put("$r:${it.gfx}", ObjectExport.manifestEntry(it)) }
              }
            })
        put(
            "players",
            buildJsonObject {
              objects.forEach { (r, o, sprites) ->
                fun sheet(female: Boolean) =
                    o.playerGfx(female)?.let { id -> sprites.firstOrNull { it.gfx == id } }
                put(
                    "$r",
                    buildJsonObject {
                      sheet(false)?.let {
                        put("male", it.sheet)
                        put("maleGfx", it.gfx)
                      }
                      sheet(true)?.let {
                        put("female", it.sheet)
                        put("femaleGfx", it.gfx)
                      }
                    })
              }
            })
        put("pokemon", buildJsonObject { mons.forEach { (dex, o) -> put("$dex", o) } })
        val music = musicJobs.map { it.get() }
        put("music", buildJsonObject { music.forEach { (r, _, p) -> p?.let { put("$r", it) } } })
        put(
            "battleMusic",
            buildJsonObject {
              music.forEach { (r, _, p) ->
                p ?: return@forEach
                val songs = File(decomp, REGIONS.getValue(r).dir + "/sound/songs/midi")
                put(
                    "$r",
                    buildJsonObject {
                      MusicExport.BATTLE.forEach { (k, c) ->
                        if (File(songs, c.lowercase() + ".mid").isFile) put(k, c)
                      }
                    })
              }
            })
        put("ui", buildJsonObject { put("font", kotlinx.serialization.json.JsonNull) })
        // r3-battle: trainer / player-back sprites, battle backgrounds, animation sprites, and the
        // extra battle music keys (elite / champion / rival / team / pvp).
        put("battle", battleAssets.export())
        put(
            "battleMusicExtra",
            buildJsonObject {
              REGIONS.keys.forEach { r -> put("$r", battleAssets.battleMusic(r)) }
            })
      }
      out.write(
          "manifest.json", Json.encodeToString(JsonObject.serializer(), manifest).toByteArray())
      val pruned = out.prune()
      val failures =
          maps.failures +
              maps.animFailures +
              objects.flatMap { it.second.failures } +
              pokemon.flatMap { it.failures } +
              battleAssets.failures
      return Result(
          entries.sumOf { it.size },
          layoutFiles.values.count { it != null },
          objects.sumOf { it.third.size },
          mons.size,
          failures.sorted(),
          out.bytes.get(),
          pruned,
          System.currentTimeMillis() - start)
    } finally {
      pool.shutdown()
    }
  }

  /**
   * FireRed's sprite first (its redrawn Kanto art, as the desktop battle screen), Emerald's next.
   */
  private fun exportMon(out: Output, sources: List<PokemonExport>, dex: Int): JsonObject? {
    val images = sources.mapNotNull { it.images(dex) }
    val front = images.firstNotNullOfOrNull { it.front }
    val back = images.firstNotNullOfOrNull { it.back }
    val icon = images.firstNotNullOfOrNull { it.icon }
    if (front == null && back == null && icon == null) return null
    return buildJsonObject {
      front?.let { put("front", out.writePng("pokemon/$dex.front.png", it)) }
      back?.let { put("back", out.writePng("pokemon/$dex.back.png", it)) }
      icon?.let { put("icon", out.writePng("pokemon/$dex.icon.png", it)) }
    }
  }
}
