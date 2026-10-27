package de.fiereu.openmmo.progression

/**
 * Emerald's secret bases (r4), parsed from [SecretBaseTables] (generated from the decomp by
 * codegen/tools/gen_secret_base_data.py). Shared by the server and its tests.
 */
object SecretBaseData {
  /** A bg_event `secret_base`: the indent / tree / shrub at ([x], [y]) on [map]. */
  data class Spot(val map: String, val x: Int, val y: Int, val id: Int) {
    /** SECRET_BASE_ID_TO_GROUP: the base map. */
    val group: Int
      get() = id / 10
  }

  /** One base map (sSecretBaseEntrancePositions + its layout). */
  data class BaseMap(
      val group: Int,
      val map: String,
      val entranceWarp: Int,
      /** Where a newly made base puts the player: in front of the PC. */
      val pcX: Int,
      val pcY: Int,
      val width: Int,
      val height: Int,
      val metatiles: IntArray,
      /** The raw MB_* name of every cell. */
      val behaviors: Array<String>,
  ) {
    fun behaviorAt(x: Int, y: Int): String? =
        if (x in 0 until width && y in 0 until height) behaviors[y * width + x] else null

    fun metatileAt(x: Int, y: Int): Int? =
        if (x in 0 until width && y in 0 until height) metatiles[y * width + x] else null
  }

  /** gDecorations: [tiles] are secondary-tileset indices, or one object gfx for [sprite]s. */
  data class Decor(
      val id: Int,
      val permission: Permission,
      val width: Int,
      val height: Int,
      val tiles: List<Int>,
  ) {
    val sprite: Boolean
      get() = permission == Permission.SPRITE

    /** The OBJ_EVENT_GFX_* of a doll / cushion, or -1. */
    val gfx: Int
      get() = if (sprite) -1 - tiles.first() else -1
  }

  /** DecorationPermission (decoration.h), in order. */
  enum class Permission {
    SOLID_FLOOR,
    PASS_FLOOR,
    BEHIND_FLOOR,
    NA_WALL,
    SPRITE,
  }

  /** The MB name and layer type of a decoration metatile (secondary index). */
  data class TileAttr(val behavior: String, val layer: Int)

  val spots: List<Spot> =
      SecretBaseTables.SPOTS.split(';')
          .filter { it.isNotEmpty() }
          .map {
            val (m, x, y, id) = it.split(':')
            Spot(m, x.toInt(), y.toInt(), id.toInt())
          }

  private val mbNames: Map<Int, String> =
      SecretBaseTables.MB_NAMES.split(';').associate {
        val (v, n) = it.split(':')
        v.toInt(16) to n
      }

  val baseMaps: List<BaseMap> =
      SecretBaseTables.GROUPS.split('|').mapIndexed { g, e ->
        val p = e.split('/')
        val cells = p[6].split(',')
        BaseMap(
            group = g,
            map = p[0],
            entranceWarp = p[1].toInt(),
            pcX = p[2].toInt(),
            pcY = p[3].toInt(),
            width = p[4].toInt(),
            height = p[5].toInt(),
            metatiles = IntArray(cells.size) { cells[it].substringBefore('.').toInt(16) },
            behaviors =
                Array(cells.size) {
                  mbNames[cells[it].substringAfter('.').toInt(16)] ?: "MB_NORMAL"
                },
        )
      }

  val decorations: Map<Int, Decor> =
      SecretBaseTables.DECORATIONS.split(';').associate {
        val (id, perm, w, h, tiles) = it.split('/')
        id.toInt() to
            Decor(
                id.toInt(),
                Permission.entries[perm.toInt()],
                w.toInt(),
                h.toInt(),
                tiles.split(',').map(String::toInt),
            )
      }

  val decorTiles: Map<Int, TileAttr> =
      SecretBaseTables.DECOR_TILES.split(';').associate {
        val (i, b, l) = it.split(':')
        i.toInt() to TileAttr(mbNames[b.toInt(16)] ?: "MB_NORMAL", l.toInt())
      }

  /** Closed -> open entrance metatile ids (sSecretBaseEntranceMetatiles). */
  val entrances: Map<Int, Int> =
      SecretBaseTables.ENTRANCES.split(';').associate {
        val (c, o) = it.split(':')
        c.toInt(16) to o.toInt(16)
      }

  fun spotsOn(map: String): List<Spot> = spots.filter { it.map == map }

  fun spot(id: Int): Spot? = spots.firstOrNull { it.id == id }

  fun baseMapOf(id: Int): BaseMap? = baseMaps.getOrNull(id / 10)

  fun isBaseMap(name: String): Boolean = name.startsWith("SecretBase_")

  /** DECOR_MAX_SECRET_BASE. */
  const val MAX_DECORATIONS = 16

  /** METATILE_SecretBase_PC / _RegisterPC / _Ground. */
  const val METATILE_PC = 0x220
  const val METATILE_REGISTER_PC = 0x221
  const val METATILE_GROUND = 0x20A
}
