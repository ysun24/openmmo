package de.fiereu.openmmo.server.game.progression

import de.fiereu.network.SessionContext
import de.fiereu.openmmo.common.DynamicWarp
import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.maps.MapDef
import de.fiereu.openmmo.maps.MapManager
import de.fiereu.openmmo.server.game.services.FieldService
import de.fiereu.openmmo.server.game.services.ScriptWarpService
import de.fiereu.openmmo.server.game.session.PlayerState
import de.fiereu.openmmo.server.game.storage.CharacterStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Escape Rope from the bag (ItemUseOutOfBattle_EscapeRope). It works where the map header sets
 * `allow_escaping` (the decomp's own flag, now in our map data) and warps to the escape warp: the
 * outdoor tile the player last stood on before entering, which FieldService records on every map
 * entry ([FieldService.ESCAPE_VAR]); DIG and the field menu's ESCAPE ROPE use the same point.
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
    return map != null && map.allowEscaping && escapePoint(charId) != null
  }

  fun escapePoint(charId: Long): DynamicWarp? {
    val s = characters.getCharacter(charId) ?: return null
    val v = s.storyVars[FieldService.ESCAPE_VAR]?.takeIf { it != 0 } ?: return null
    return DynamicWarp(
        regionId = s.info.positionRegionId,
        bankId = ((v ushr 24) and 0xFF).toByte(),
        mapId = ((v ushr 16) and 0xFF).toByte(),
        x = ((v ushr 8) and 0xFF).toShort(),
        y = (v and 0xFF).toShort(),
        facing = Direction.DOWN,
    )
  }

  /** Kept for the step hook; the escape point is recorded on map entry now. */
  @Suppress("UNUSED_PARAMETER") fun onStep(charId: Long, map: MapDef, x: Int, y: Int) = Unit

  suspend fun escape(session: SessionContext, state: PlayerState, charId: Long): Boolean {
    val dest = escapePoint(charId) ?: return false
    warps.warp(session, state, dest)
    return true
  }
}
