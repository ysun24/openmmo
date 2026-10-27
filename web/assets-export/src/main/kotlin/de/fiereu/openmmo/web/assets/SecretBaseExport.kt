package de.fiereu.openmmo.web.assets

import de.fiereu.openmmo.client.gfx.GbaGame
import de.fiereu.openmmo.client.gfx.MetatilePainter
import de.fiereu.openmmo.client.gfx.MetatilePainter.Pixels
import de.fiereu.openmmo.progression.Gen3Data
import de.fiereu.openmmo.progression.SecretBaseData
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * r4: what the browser draws of Emerald's secret bases (the maps are pre-rendered images, so the
 * metatiles a base changes at run time come as atlases of 16x16 cells, row 0 the bottom layer and
 * row 1 the top layer, like `<map>.bottom/.top.png`):
 * - per base tileset (red/brown/blue/yellow cave, tree, shrub) an atlas of every decoration
 *   metatile (and its wall-background variant) plus the PC, the Register PC and plain ground;
 * - one atlas of the open entrance metatile of every spot, drawn with its own map's tilesets.
 */
class SecretBaseExport(private val out: Output) {
  /**
   * Secondary-tileset indices in atlas order (decoration tiles, then PC / Register PC / ground).
   */
  private val tiles: List<Int> =
      (SecretBaseData.decorTiles.keys.sorted() +
              listOf(
                  SecretBaseData.METATILE_PC - 512,
                  SecretBaseData.METATILE_REGISTER_PC - 512,
                  SecretBaseData.METATILE_GROUND - 512))
          .distinct()

  fun export(region: Int, game: GbaGame, entries: List<MapEntry>): JsonObject = buildJsonObject {
    val atlases = HashMap<String, String>()
    put("tiles", buildJsonArray { tiles.forEach { add(JsonPrimitive(it)) } })
    // The MB_* name and layer type of each atlas tile (the browser predicts collision with them
    // the way ShowDecorationOnMap_ sets it).
    put(
        "tileAttrs",
        buildJsonArray {
          tiles.forEach { t ->
            val a = SecretBaseData.decorTiles[t]
            add(
                buildJsonArray {
                  add(JsonPrimitive((a?.behavior ?: "MB_NORMAL").removePrefix("MB_")))
                  add(JsonPrimitive(a?.layer ?: 0))
                })
          }
        })
    put(
        "bases",
        buildJsonObject {
          for (e in entries.filter { SecretBaseData.isBaseMap(it.name) }) {
            val base = SecretBaseData.baseMaps.firstOrNull { it.map == e.name } ?: continue
            val atlas =
                atlases.getOrPut(e.name.trimEnd { it.isDigit() }) {
                  val px = 16 * tiles.size
                  val bottom = Pixels(px, 16)
                  val top = Pixels(px, 16)
                  tiles.forEachIndexed { i, t ->
                    MetatilePainter.drawMetatile(bottom, top, game, e.layout, 512 + t, i * 16, 0)
                  }
                  val img =
                      java.awt.image.BufferedImage(
                          px, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                  img.setRGB(0, 0, px, 16, bottom.data, 0, px)
                  img.setRGB(0, 16, px, 16, top.data, 0, px)
                  out.writePng("secretbase/${e.name.trimEnd { it.isDigit() }.lowercase()}.png", img)
                }
            put(
                e.key,
                buildJsonObject {
                  put("atlas", atlas)
                  put("group", base.group)
                  put("pcX", base.pcX)
                  put("pcY", base.pcY)
                  put("width", base.width)
                  // the cells drawn with MB_SECRET_BASE_NORTH_WALL / the PC (placement rules)
                  put(
                      "behaviors",
                      buildJsonArray {
                        base.behaviors.forEach { add(JsonPrimitive(it.removePrefix("MB_"))) }
                      })
                })
          }
        })
    // Every spot's open entrance, with the tilesets of the map it is on.
    val spotCells = mutableListOf<Triple<MapEntry, SecretBaseData.Spot, Int>>()
    for (e in entries) for (s in SecretBaseData.spotsOn(e.name)) {
      val closed = e.layout.metatileAt(s.x, s.y) ?: continue
      val open = SecretBaseData.entrances[closed] ?: continue
      spotCells += Triple(e, s, open)
    }
    if (spotCells.isNotEmpty()) {
      val px = 16 * spotCells.size
      val bottom = Pixels(px, 16)
      val top = Pixels(px, 16)
      spotCells.forEachIndexed { i, (e, _, open) ->
        MetatilePainter.drawMetatile(bottom, top, game, e.layout, open, i * 16, 0)
      }
      val img = java.awt.image.BufferedImage(px, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB)
      img.setRGB(0, 0, px, 16, bottom.data, 0, px)
      img.setRGB(0, 16, px, 16, top.data, 0, px)
      put("entrances", out.writePng("secretbase/entrances_$region.png", img))
    }
    put(
        "spots",
        buildJsonObject {
          spotCells
              .groupBy { it.first.key }
              .forEach { (key, cells) ->
                put(
                    key,
                    buildJsonArray {
                      cells.forEach { (_, s, _) ->
                        add(
                            buildJsonObject {
                              put("x", s.x)
                              put("y", s.y)
                              put("id", s.id)
                              put("cell", spotCells.indexOfFirst { it.second === s })
                            })
                      }
                    })
              }
        })
    put(
        "decorations",
        buildJsonObject {
          for ((id, d) in SecretBaseData.decorations) {
            val g = Gen3Data.decorations[id]
            put(
                "$id",
                buildJsonObject {
                  put("name", g?.name ?: "DECORATION $id")
                  put("category", g?.category ?: "")
                  put("perm", d.permission.name.lowercase())
                  put("w", d.width)
                  put("h", d.height)
                  if (d.sprite) put("gfx", d.gfx)
                  else put("tiles", buildJsonArray { d.tiles.forEach { add(JsonPrimitive(it)) } })
                })
          }
        })
  }
}
