package de.fiereu.openmmo.server.game.services.social

import de.fiereu.network.SessionContext
import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.maps.MapDef
import de.fiereu.openmmo.maps.MapManager
import de.fiereu.openmmo.net.game.packets.NpcSpawnPacket
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Npcs that are not in the decomp's map data but are added by the server, spawned together with a
 * map's own npcs. [NpcService] asks every source when it spawns a map.
 */
interface ExtraNpcSource {
  fun spawn(ctx: SessionContext, regionId: Int, bankId: Int, mapId: Int)

  companion object {
    val NONE =
        object : ExtraNpcSource {
          override fun spawn(ctx: SessionContext, regionId: Int, bankId: Int, mapId: Int) {}
        }
  }
}

/** Where a market clerk stands on one map. */
data class ClerkSpot(val entityId: Long, val x: Int, val y: Int)

/**
 * A market clerk in every Pokemon Center (every map with a nurse), standing in a free corner of the
 * lobby. Talking to them opens the global market. Gen 3 has no market, so the clerk borrows the
 * Poke Mart clerk sprite of the region (FRLG `OBJ_EVENT_GFX_CLERK` 68, Emerald
 * `OBJ_EVENT_GFX_MART_EMPLOYEE` 83).
 */
@Singleton
class MarketClerks @Inject constructor(private val maps: MapManager) : ExtraNpcSource {
  private val spots = ConcurrentHashMap<String, ClerkSpot?>()
  private val byEntity = ConcurrentHashMap<Long, Unit>()

  fun spotFor(regionId: Int, bankId: Int, mapId: Int): ClerkSpot? =
      spots
          .computeIfAbsent("$regionId:$bankId:$mapId") {
            maps.getMap(regionId, bankId, mapId)?.let(::findSpot)?.let { (x, y) ->
              ClerkSpot(entityId(regionId, bankId, mapId), x, y)
            }
          }
          ?.also { byEntity[it.entityId] = Unit }

  fun isClerk(entityId: Long): Boolean = byEntity.containsKey(entityId)

  override fun spawn(ctx: SessionContext, regionId: Int, bankId: Int, mapId: Int) {
    val spot = spotFor(regionId, bankId, mapId) ?: return
    ctx.send(
        NpcSpawnPacket(
            entityId = spot.entityId,
            spriteRegionId = regionId,
            graphicsId = if (regionId == 0) KANTO_CLERK_GFX else HOENN_CLERK_GFX,
            // Movement type 0 (none) in the high byte, like a standing npc's spawn.
            unk3 = 0x02,
            unk4 = 0,
            regionId = regionId,
            bankId = bankId,
            mapId = mapId,
            x = spot.x,
            y = spot.y,
            facing = Direction.DOWN.ordinal,
            unk5 = 2,
            unk6 = 8,
        ))
  }

  companion object {
    const val KANTO_CLERK_GFX = 68
    const val HOENN_CLERK_GFX = 83

    /** Lobby corners left and right of the counter, in the order they are tried. */
    private val CANDIDATES = listOf(1 to 2, 2 to 2, 0 to 2, 3 to 2, 12 to 2, 13 to 2, 11 to 2)

    /** Above the npc id range NpcService allocates from, keyed by map. */
    fun entityId(regionId: Int, bankId: Int, mapId: Int): Long =
        0x1A6A000000000000L or
            (regionId.toLong() shl 16) or
            (bankId.toLong() shl 8) or
            mapId.toLong()

    /**
     * The first candidate tile that is walkable and free of npcs, warps, signs and the tiles in
     * front of the nurse. Null on maps without a nurse.
     */
    fun findSpot(map: MapDef): Pair<Int, Int>? {
      val nurse = map.npcs.firstOrNull { it.script.endsWith("_EventScript_Nurse") } ?: return null
      val taken =
          map.npcs.flatMap { npc ->
            // A wandering npc may step next to its home tile, so keep a margin around it.
            (-1..1).flatMap { dx -> (-1..1).map { dy -> (npc.x + dx) to (npc.y + dy) } }
          } + map.warps.map { it.x to it.y } + map.bgEvents.map { it.x to it.y }
      // The way to the nurse's counter stays clear.
      val counter = (0..3).map { nurse.x to (nurse.y + it) }
      fun free(x: Int, y: Int) =
          x in 0 until map.width &&
              y in 0 until map.height &&
              map.tileAt(x, y)?.blocksMovement() == false &&
              (x to y) !in taken &&
              (x to y) !in counter &&
              // Something has to be walkable below, where the player stands to talk.
              map.tileAt(x, y + 1)?.blocksMovement() == false
      CANDIDATES.firstOrNull { (x, y) -> free(x, y) }
          ?.let {
            return it
          }
      // Other layouts (Trainer Tower, Battle Frontier): the free tile nearest the nurse.
      return (0 until map.height)
          .flatMap { y -> (0 until map.width).map { x -> x to y } }
          .filter { (x, y) -> free(x, y) }
          .minByOrNull { (x, y) -> kotlin.math.abs(x - nurse.x) + kotlin.math.abs(y - nurse.y) * 2 }
    }
  }
}
