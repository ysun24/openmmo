package de.fiereu.openmmo.server.game.progression

import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.maps.MapManager
import de.fiereu.openmmo.maps.WarpTile
import de.fiereu.openmmo.server.game.field.FlyDestinations
import de.fiereu.openmmo.server.game.field.FlyTown
import de.fiereu.openmmo.server.game.session.PlayerState
import de.fiereu.openmmo.server.game.storage.CharacterStore
import de.fiereu.openmmo.server.game.storage.StoredCharacter
import javax.inject.Inject
import javax.inject.Singleton

/** Where a white-out puts the player back: a map tile and the facing. */
data class RespawnPoint(
    val regionId: Int,
    val bankId: Int,
    val mapId: Int,
    val x: Int,
    val y: Int,
    val facing: Direction,
)

/**
 * The last heal location (the decomp's `gSaveBlock1Ptr->lastHealLocation`). It is recorded when a
 * Pokemon Center nurse (or Mom) heals the party, as story vars, and read by TELEPORT (the town's
 * heal location) and by a white-out:
 * - FireRed `SetWhiteoutRespawnWarpAndHealerNpc`: inside the center in front of the nurse (7, 4;
 *   Pallet's house 8, 5; Indigo Plateau 13, 12; One Island 5, 4);
 * - Emerald `SetWarpDestinationToLastHealLocation`: the town's heal location tile.
 */
@Singleton
class HealLocations
@Inject
constructor(
    private val characters: CharacterStore,
    private val maps: MapManager,
) {

  /** Records the heal location of the map the player stands on, if it is one (a center, home). */
  fun recordHeal(state: PlayerState) {
    val charId = state.characterId ?: return
    val map = maps.getMap(state.regionId, state.bankId, state.mapId) ?: return
    val region = Region.byWireValue(state.regionId.toByte()) ?: return
    val town = townOf(region, map.name) ?: return
    val index = FlyDestinations.all.indexOf(town) + 1
    characters.setStoryVars(
        charId,
        mapOf(
            HEAL_TOWN to index,
            HEAL_MAP to ((state.regionId shl 16) or (state.bankId shl 8) or state.mapId),
        ))
  }

  /** The town a heal on map [mapName] belongs to: `<Town>_PokemonCenter_1F`, the player's home. */
  fun townOf(region: Region, mapName: String): FlyTown? {
    val healer =
        mapName.contains("PokemonCenter_1F") ||
            mapName.contains("PlayersHouse") ||
            mapName.contains("BrendansHouse") ||
            mapName.contains("MaysHouse")
    if (!healer) return null
    val prefix = mapName.substringBefore("_")
    return FlyDestinations.byMapName(region, prefix)
        ?: FlyDestinations.forRegion(region).firstOrNull { mapName.startsWith(it.mapName + "_") }
  }

  /**
   * The last heal location's town in [region], or null when the player has healed nowhere there.
   */
  fun lastTown(stored: StoredCharacter, region: Region): FlyTown? =
      FlyDestinations.all.getOrNull((stored.storyVars[HEAL_TOWN] ?: 0) - 1)?.takeIf {
        it.region == region
      }

  /**
   * Where a white-out in [region] respawns: the last heal location, else the region's first town
   * (Pallet / Littleroot), as a new game would.
   */
  fun respawn(stored: StoredCharacter, region: Region, female: Boolean): RespawnPoint? {
    val town = lastTown(stored, region) ?: FlyDestinations.forRegion(region).firstOrNull()
    val packed = stored.storyVars[HEAL_MAP]?.takeIf { it != 0 && lastTown(stored, region) != null }
    if (region == Region.KANTO && packed != null) {
      val r = packed ushr 16
      val b = (packed ushr 8) and 0xFF
      val m = packed and 0xFF
      val name = maps.getMap(r, b, m)?.name.orEmpty()
      val (x, y) =
          when {
            name.startsWith("PalletTown_PlayersHouse") -> 8 to 5
            name.startsWith("IndigoPlateau_PokemonCenter") -> 13 to 12
            name.startsWith("OneIsland_PokemonCenter") -> 5 to 4
            else -> 7 to 4
          }
      return RespawnPoint(r, b, m, x, y, Direction.UP)
    }
    town ?: return null
    val target = maps.getMapByName(region.wireValue.toInt(), town.mapName) ?: return null
    return RespawnPoint(
        target.regionId.toInt(),
        target.bankId.toInt() and 0xFF,
        target.mapId.toInt() and 0xFF,
        if (female) town.femaleX else town.x,
        if (female) town.femaleY else town.y,
        Direction.DOWN)
  }

  fun warpTile(p: RespawnPoint): WarpTile =
      WarpTile(
          x = 0,
          y = 0,
          targetRegionId = p.regionId.toByte(),
          targetBankId = p.bankId.toByte(),
          targetMapId = p.mapId.toByte(),
          targetX = p.x,
          targetY = p.y,
          exitFacing = p.facing,
      )

  companion object {
    const val HEAL_TOWN = "openmmo/HEAL_TOWN"
    const val HEAL_MAP = "openmmo/HEAL_MAP"

    /** FireRed sWhiteOutMoneyLossMultipliers, by badge count. */
    private val LOSS_MULTIPLIERS = intArrayOf(2, 4, 6, 9, 12, 16, 20, 25, 30)

    /**
     * The money a white-out costs: FireRed `ComputeWhiteOutMoneyLoss` (highest level x 4 x the
     * badge multiplier, capped at what the player has), Emerald `DoWhiteOut` (half the money).
     */
    fun moneyLoss(region: Region, money: Int, badges: Int, highestLevel: Int): Int =
        if (region == Region.HOENN) money - money / 2
        else (highestLevel * 4 * LOSS_MULTIPLIERS[badges.coerceIn(0, 8)]).coerceAtMost(money)
  }
}
