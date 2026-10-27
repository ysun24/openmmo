package de.fiereu.openmmo.web.assets

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * r3-battle: battle presentation assets from the decomps, all under `battle/`:
 * - trainer front sprites keyed by the decomp's `TRAINER_PIC_` suffix in lower case
 *   (`battle/trainers/leader_roxanne.png`), resolved through front_pic_tables.h, Emerald first and
 *   FireRed for the Kanto-only pictures;
 * - the player's back sprites (the throw animation frames stacked vertically): Brendan / May
 *   (Emerald), Red / Leaf (FireRed);
 * - battle backgrounds per environment (`battle/bg/<region>/<key>.png`), rendered from the
 *   environment's 4bpp tiles, tilemap and palette the way battle_bg.c loads them (palette slot 2),
 *   with the first 240x160 screen kept;
 * - a set of battle animation sprite sheets per move type (graphics/battle_anims/sprites), with
 *   their frame size.
 */
class BattleAssetsExport(private val decompRoot: File, private val out: Output) {
  val failures: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

  private val emerald = Decomp(File(decompRoot, "pokeemerald"))
  private val firered = Decomp(File(decompRoot, "pokefirered"))

  fun export(): JsonObject = buildJsonObject {
    put("trainers", trainers())
    put("backs", backs())
    put("backgrounds", backgrounds())
    put("anims", anims())
  }

  // --- trainers ----------------------------------------------------------------------------------

  /** TRAINER_PIC_ suffix -> (front pic symbol, palette symbol), from front_pic_tables.h. */
  private fun picTable(d: Decomp): Map<String, Pair<String, String?>> {
    val f = File(d.base, "src/data/trainer_graphics/front_pic_tables.h")
    if (!f.isFile) return emptyMap()
    val text = f.readText()
    val sprites =
        Regex("""TRAINER_SPRITE\(\s*(\w+)\s*,\s*(\w+)""").findAll(text).associate {
          it.groupValues[1] to it.groupValues[2]
        }
    val pals =
        Regex("""TRAINER_PAL\(\s*(\w+)\s*,\s*(\w+)""").findAll(text).associate {
          it.groupValues[1] to it.groupValues[2]
        }
    return sprites.mapValues { (k, v) -> v to pals[k] }
  }

  private fun image(d: Decomp, picSymbol: String, palSymbol: String?): BufferedImage? {
    val png = d.symbols[picSymbol]?.firstOrNull()?.let(d::file) ?: return null
    val pal =
        palSymbol
            ?.let { d.symbols[it]?.firstOrNull() }
            ?.takeIf { it.endsWith(".pal") }
            ?.let(d::file)
    return Decomp.readIndexed(png, Decomp.readPalette(pal))
  }

  private fun trainers(): JsonObject = buildJsonObject {
    val done = mutableSetOf<String>()
    for (d in listOf(emerald, firered)) {
      for ((key, syms) in picTable(d)) {
        val name = key.lowercase()
        if (name in done) continue
        runCatching {
              val img = image(d, syms.first, syms.second) ?: return@runCatching
              if (Decomp.isEmpty(img)) return@runCatching
              put(name, out.writePng("battle/trainers/$name.png", img))
              done += name
            }
            .onFailure { failures += "trainer $name: $it" }
      }
    }
  }

  // --- player back sprites -----------------------------------------------------------------------

  private fun backs(): JsonObject = buildJsonObject {
    val list =
        listOf(
            Triple(
                "brendan",
                emerald,
                "graphics/trainers/back_pics/brendan.png" to
                    "graphics/trainers/palettes/brendan.pal"),
            Triple(
                "may",
                emerald,
                "graphics/trainers/back_pics/may.png" to "graphics/trainers/palettes/may.pal"),
            Triple(
                "red",
                firered,
                "graphics/trainers/back_pics/red_back_pic.png" to
                    "graphics/trainers/palettes/red_back_pic.pal"),
            Triple(
                "leaf",
                firered,
                "graphics/trainers/back_pics/leaf_back_pic.png" to
                    "graphics/trainers/palettes/leaf_back_pic.pal"),
        )
    for ((name, d, paths) in list) {
      runCatching {
            val png = d.file(paths.first) ?: return@runCatching
            val img = Decomp.readIndexed(png, Decomp.readPalette(d.file(paths.second)))
            val rel = out.writePng("battle/backs/$name.png", img)
            put(
                name,
                buildJsonObject {
                  put("file", rel)
                  put("frame", img.width)
                  put("frames", img.height / img.width)
                })
          }
          .onFailure { failures += "back $name: $it" }
    }
  }

  // --- backgrounds -------------------------------------------------------------------------------

  /** Background key -> (tiles/tilemap dir, palette file) for Emerald (region 1). */
  private val emeraldBgs =
      linkedMapOf(
          "tall_grass" to ("tall_grass" to "tall_grass/palette.pal"),
          "long_grass" to ("long_grass" to "long_grass/palette.pal"),
          "sand" to ("sand" to "sand/palette.pal"),
          "underwater" to ("underwater" to "underwater/palette.pal"),
          "water" to ("water" to "water/palette.pal"),
          "pond_water" to ("pond_water" to "pond_water/palette.pal"),
          "rock" to ("rock" to "rock/palette.pal"),
          "cave" to ("cave" to "cave/palette.pal"),
          "building" to ("building" to "building/palette.pal"),
          "plain" to ("building" to "plain/palette.pal"),
          "gym" to ("building" to "building/gym.pal"),
          "leader" to ("building" to "building/leader.pal"),
          "stadium" to ("stadium" to "stadium/battle_frontier.pal"),
          "elite_sidney" to ("stadium" to "stadium/sidney.pal"),
          "elite_phoebe" to ("stadium" to "stadium/phoebe.pal"),
          "elite_glacia" to ("stadium" to "stadium/glacia.pal"),
          "elite_drake" to ("stadium" to "stadium/drake.pal"),
          "champion" to ("stadium" to "stadium/wallace.pal"),
          "team_aqua" to ("stadium" to "stadium/aqua.pal"),
          "team_magma" to ("stadium" to "stadium/magma.pal"),
      )

  /** The same for FireRed (region 0), graphics/battle_terrain. */
  private val fireredBgs =
      linkedMapOf(
          "tall_grass" to ("grass" to "grass/terrain.pal"),
          "long_grass" to ("longgrass" to "longgrass/terrain.pal"),
          "sand" to ("sand" to "sand/terrain.pal"),
          "underwater" to ("underwater" to "underwater/terrain.pal"),
          "water" to ("water" to "water/terrain.pal"),
          "pond_water" to ("pond" to "pond/terrain.pal"),
          "rock" to ("mountain" to "mountain/terrain.pal"),
          "cave" to ("cave" to "cave/terrain.pal"),
          "building" to ("building" to "building/terrain.pal"),
          "plain" to ("indoor" to "indoor/plain.pal"),
          "gym" to ("indoor" to "indoor/gym.pal"),
          "leader" to ("indoor" to "indoor/leader.pal"),
          "stadium" to ("indoor" to "indoor/link.pal"),
          "elite_lorelei" to ("indoor" to "indoor/lorelei.pal"),
          "elite_bruno" to ("indoor" to "indoor/bruno.pal"),
          "elite_agatha" to ("indoor" to "indoor/agatha.pal"),
          "elite_lance" to ("indoor" to "indoor/lance.pal"),
          "champion" to ("indoor" to "indoor/champion.pal"),
      )

  private fun backgrounds(): JsonObject = buildJsonObject {
    for ((region, bgs, root, names) in
        listOf(
            Quad(1, emeraldBgs, "graphics/battle_environment", "tiles.png" to "map.bin"),
            Quad(0, fireredBgs, "graphics/battle_terrain", "terrain.png" to "terrain.bin"))) {
      val base = if (region == 1) emerald.base else firered.base
      put(
          "$region",
          buildJsonObject {
            for ((key, cfg) in bgs) {
              runCatching {
                    val dir = File(base, "$root/${cfg.first}")
                    val img =
                        renderBg(
                            File(dir, names.first),
                            File(dir, names.second),
                            File(base, "$root/${cfg.second}")) ?: return@runCatching
                    put(key, out.writePng("battle/bg/$region/$key.png", img))
                  }
                  .onFailure { failures += "bg $region $key: $it" }
            }
          })
    }
  }

  private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

  /**
   * One battle background: 4bpp tiles + a GBA text tilemap (tile 0-9, h flip 10, v flip 11, palette
   * 12-15) + the environment's palettes, loaded at palette slot 2 as battle_bg.c does. The visible
   * 240x160 screen is kept.
   */
  internal fun renderBg(tilesPng: File, mapBin: File, palFile: File): BufferedImage? {
    if (!tilesPng.isFile || !mapBin.isFile || !palFile.isFile) return null
    val tiles = ImageIO.read(tilesPng)
    val raster = tiles.raster
    val pal = PretDataPalette.read(palFile)
    val map = mapBin.readBytes()
    val tilesPerRow = tiles.width / 8
    val mapWidth = 32
    val img = BufferedImage(240, 160, BufferedImage.TYPE_INT_ARGB)
    val px = IntArray(4)
    for (ty in 0 until 20) for (tx in 0 until 30) {
      val i = (ty * mapWidth + tx) * 2
      if (i + 1 >= map.size) continue
      val entry = (map[i].toInt() and 0xFF) or ((map[i + 1].toInt() and 0xFF) shl 8)
      val tile = entry and 0x3FF
      val hflip = entry and 0x400 != 0
      val vflip = entry and 0x800 != 0
      val palNum = (entry ushr 12) and 0xF
      val base = ((palNum - 2).coerceAtLeast(0)) * 16
      val sx = (tile % tilesPerRow) * 8
      val sy = (tile / tilesPerRow) * 8
      if (sy + 8 > tiles.height) continue
      for (y in 0 until 8) for (x in 0 until 8) {
        val srcX = sx + if (hflip) 7 - x else x
        val srcY = sy + if (vflip) 7 - y else y
        val idx = raster.getPixel(srcX, srcY, px)[0] and 0xF
        val colour = if (idx == 0) pal.getOrNull(0) ?: 0 else pal.getOrNull(base + idx) ?: 0
        img.setRGB(tx * 8 + x, ty * 8 + y, colour or (0xFF shl 24))
      }
    }
    return img
  }

  // --- animation sprites
  // ---------------------------------------------------------------------------

  /** Move type (or effect) -> battle_anims/sprites file stem. */
  private val animSprites =
      linkedMapOf(
          "NORMAL" to "impact",
          "FIRE" to "small_ember",
          "WATER" to "water_orb",
          "GRASS" to "leaf",
          "ELECTRIC" to "shock",
          "ICE" to "ice_crystals_0",
          "POISON" to "poison_bubble",
          "FIGHTING" to "punch_impact",
          "GROUND" to "mud_sand_0",
          "ROCK" to "rocks",
          "FLYING" to "white_feather",
          "PSYCHIC" to "hollow_orb",
          "BUG" to "needle",
          "GHOST" to "ghostly_spirit",
          "DRAGON" to "purple_flame",
          "DARK" to "purple_swipe",
          "STEEL" to "metal_ball",
          "hit" to "impact",
          "sleep" to "z",
          "ball" to "pokeball",
          "sparkle" to "sparkle_1",
          "stat" to "focus_energy",
      )

  private fun anims(): JsonObject = buildJsonObject {
    for ((key, stem) in animSprites) {
      runCatching {
            val png =
                File(emerald.base, "graphics/battle_anims/sprites/$stem.png").takeIf { it.isFile }
                    ?: File(firered.base, "graphics/battle_anims/sprites/$stem.png").takeIf {
                      it.isFile
                    }
                    ?: return@runCatching
            val pal = File(png.parentFile, "$stem.pal").takeIf { it.isFile }
            val img = Decomp.readIndexed(png, Decomp.readPalette(pal))
            val rel = out.writePng("battle/anims/$stem.png", img)
            val frame = minOf(img.width, img.height)
            put(
                key,
                buildJsonObject {
                  put("file", rel)
                  put("frame", frame)
                  put("frames", if (img.height % frame == 0) img.height / frame else 1)
                })
          }
          .onFailure { failures += "anim $key: $it" }
    }
  }

  /** The battle music keys the browser can ask for, per region (MusicExport.BATTLE plus these). */
  fun battleMusic(region: Int): JsonObject {
    val dir = if (region == 1) emerald.base else firered.base
    val songs = File(dir, "sound/songs/midi")
    val keys =
        if (region == 1)
            linkedMapOf(
                "elite" to "MUS_VS_ELITE_FOUR",
                "champion" to "MUS_VS_CHAMPION",
                "rival" to "MUS_VS_RIVAL",
                "team" to "MUS_VS_AQUA_MAGMA",
                "pvp" to "MUS_VS_TRAINER",
                "victoryElite" to "MUS_VICTORY_LEAGUE")
        else
            // FireRed GetBattleBGM: leaders and the Elite Four share the gym theme, link battles
            // play the RS trainer theme, everything else the trainer theme.
            linkedMapOf(
                "elite" to "MUS_VS_GYM_LEADER",
                "champion" to "MUS_VS_CHAMPION",
                "rival" to "MUS_VS_TRAINER",
                "team" to "MUS_VS_TRAINER",
                "pvp" to "MUS_RS_VS_TRAINER",
                "victoryElite" to "MUS_VICTORY_GYM_LEADER")
    return buildJsonObject {
      keys.forEach { (k, c) -> if (File(songs, c.lowercase() + ".mid").isFile) put(k, c) }
    }
  }
}

/** A JASC palette as ARGB, any number of colours. */
internal object PretDataPalette {
  fun read(file: File): IntArray {
    val lines = file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    val count = lines.getOrNull(2)?.toIntOrNull() ?: 0
    return IntArray(count) { i ->
      val p = lines.getOrNull(3 + i)?.split(Regex("\\s+"))?.map { it.toInt() } ?: listOf(0, 0, 0)
      (0xFF shl 24) or (p[0] shl 16) or (p[1] shl 8) or p[2]
    }
  }
}
