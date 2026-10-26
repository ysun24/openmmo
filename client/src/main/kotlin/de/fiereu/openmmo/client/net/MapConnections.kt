package de.fiereu.openmmo.client.net

import de.fiereu.openmmo.client.world.KnownConnection
import de.fiereu.openmmo.client.world.KnownMap
import de.fiereu.openmmo.client.world.MapKey
import de.fiereu.openmmo.client.world.OverworldState
import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.net.game.packets.LoadMapPacket
import de.fiereu.openmmo.net.game.packets.MapData

/**
 * Seamless map connections. The server preloads every connected map with a LoadMap and, when the
 * player walks off an edge, just moves it onto the neighbour without a reload, so the client has to
 * make the same move itself, with the same arithmetic as the server's MovementService.
 */
object MapConnections {
  fun remember(state: OverworldState, p: LoadMapPacket) {
    val data = p.mapData as? MapData.GbaMapData ?: return
    val key = MapKey(p.regionId, p.bankId, p.mapId)
    state.rememberMap(
        KnownMap(
            key,
            data.width,
            data.height,
            data.connections.map {
              KnownConnection(it.direction, it.unknown, MapKey(p.regionId, it.targetBank, it.targetMap))
            },
        ))
  }

  /** The map and tile a step off [from]'s edge to ([tx], [ty]) lands on, or null if none. */
  fun cross(
      state: OverworldState,
      from: MapKey,
      direction: Direction,
      tx: Int,
      ty: Int,
  ): Triple<KnownMap, Int, Int>? {
    val conn = state.knownMap(from)?.connections?.find { it.direction == direction } ?: return null
    val target = state.knownMap(conn.target) ?: return null
    val (nx, ny) =
        when (direction) {
          Direction.UP -> (tx - conn.offset).coerceIn(0, target.width - 1) to target.height - 1
          Direction.DOWN -> (tx - conn.offset).coerceIn(0, target.width - 1) to 0
          Direction.LEFT -> target.width - 1 to (ty - conn.offset).coerceIn(0, target.height - 1)
          Direction.RIGHT -> 0 to (ty - conn.offset).coerceIn(0, target.height - 1)
          else -> return null
        }
    return Triple(target, nx, ny)
  }
}
