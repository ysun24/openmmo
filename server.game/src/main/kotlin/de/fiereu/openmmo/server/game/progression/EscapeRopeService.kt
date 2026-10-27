package de.fiereu.openmmo.server.game.progression

import de.fiereu.network.SessionContext
import de.fiereu.openmmo.common.DynamicWarp
import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.common.enums.MapType
import de.fiereu.openmmo.maps.MapDef
import de.fiereu.openmmo.maps.MapManager
import de.fiereu.openmmo.server.game.services.ScriptWarpService
import de.fiereu.openmmo.server.game.session.PlayerState
import de.fiereu.openmmo.server.game.storage.CharacterStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Escape Rope (ItemUseOutOfBattle_EscapeRope). The decomp allows it where the map header sets
 * `allowEscaping` and warps to the escape warp recorded when the player last entered such a map
 * from outside. Our map data has no `allowEscaping`, so caves (MAP_TYPE_UNDERGROUND) stand in for
 * it, and the escape point is the last tile the player stood on outdoors (a town, city or route),
 * kept as story vars so it survives a relog.
 */
@Singleton
class EscapeRopeService
@Inject
constructor(
    private val characters: CharacterStore,
    private val maps: MapManager,
    private val warps: ScriptWarpService,
) {
  fun canEscape(charId: Long): Boolean {
    val s = characters.getCharacter(charId) ?: return false
    val map = maps.getMap(s.info.positionRegionId, s.info.positionBankId, s.info.positionMapId)
    return map != null && map.mapType == MapType.UNDERGROUND && escapePoint(charId) != null
  }

  fun escapePoint(charId: Long): DynamicWarp? {
    val v = characters.getCharacter(charId)?.storyVars ?: return null
    val region = v[REGION] ?: return null
    return DynamicWarp(
        regionId = (region - 1).toByte(),
        bankId = (v[BANK] ?: 0).toByte(),
        mapId = (v[MAP] ?: 0).toByte(),
        x = (v[X] ?: 0).toShort(),
        y = (v[Y] ?: 0).toShort(),
        facing = Direction.DOWN,
    )
  }

  /** Remembers an outdoor tile; called for every step. */
  fun onStep(charId: Long, map: MapDef, x: Int, y: Int) {
    if (map.mapType != MapType.CITY && map.mapType != MapType.ROUTE) return
    characters.setStoryVars(
        charId,
        mapOf(
            // Region is stored +1 so Kanto (0) is not mistaken for an unset var.
            REGION to map.regionId + 1,
            BANK to map.bankId.toInt(),
            MAP to map.mapId.toInt(),
            X to x,
            Y to y,
        ))
  }

  suspend fun escape(session: SessionContext, state: PlayerState, charId: Long): Boolean {
    val dest = escapePoint(charId) ?: return false
    warps.warp(session, state, dest)
    return true
  }

  private companion object {
    const val REGION = "openmmo/ESCAPE_REGION"
    const val BANK = "openmmo/ESCAPE_BANK"
    const val MAP = "openmmo/ESCAPE_MAP"
    const val X = "openmmo/ESCAPE_X"
    const val Y = "openmmo/ESCAPE_Y"
  }
}
