package de.fiereu.openmmo.server.game.services

import de.fiereu.network.SessionContext
import de.fiereu.openmmo.common.enums.EncounterMethod
import de.fiereu.openmmo.common.enums.TileBehavior
import de.fiereu.openmmo.maps.MapDef
import de.fiereu.openmmo.maps.WildEncounterSlot
import de.fiereu.openmmo.maps.WildEncounterTable
import de.fiereu.openmmo.server.game.storage.CharacterStore
import io.github.oshai.kotlinlogging.KotlinLogging
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

private val log = KotlinLogging.logger {}

// The GBA rolls a land encounter out of 2880 with the table rate scaled by 16.
private const val ENCOUNTER_ROLL_MAX = 2880
private const val ENCOUNTER_RATE_SCALE = 16

/** Rolls a wild encounter when a player steps onto grass and starts the battle if one is met. */
@Singleton
class EncounterService
@Inject
constructor(
    private val characterStore: CharacterStore,
    private val battleService: BattleService,
) {

  private val random: Random = Random.Default

  /** Called after a completed step. Starts a wild battle if the tile and roll call for one. */
  fun onStep(session: SessionContext, charId: Long, map: MapDef, x: Int, y: Int) {
    if (battleService.inBattle(charId)) return
    val repelled = tickRepel(session, charId)
    val tile = map.tileAt(x, y) ?: return
    if (!isLandEncounterTile(tile.behavior)) return
    // TODO: Add water and fishing wild encounters
    //  Water encounters should fire while surfing over water tiles and fishing when a rod is used.
    //  Both need the surf and rod features to exist first. Once they do, branch here on the tile
    //  behavior and roll the WATER or FISHING table the same way land is rolled below.
    val table = map.encounterTable(EncounterMethod.LAND) ?: return
    if (!hasUsablePartyMon(charId)) return
    if (!rollsEncounter(table)) return
    val slot = pickSlot(table) ?: return
    val level = random.nextInt(slot.minLevel, slot.maxLevel + 1)
    // The GBA repel: a wild monster below the lead's level does not appear.
    if (repelled && level < leadLevel(charId)) return
    log.info {
      "Wild encounter for char=$charId at ($x, $y): species ${slot.speciesId} level $level"
    }
    battleService.startWildBattle(session, slot.speciesId, level)
  }

  /** Counts a repel down by one step; true while one is active. Tells the player when it ends. */
  private fun tickRepel(session: SessionContext, charId: Long): Boolean {
    val info = characterStore.getCharacter(charId)?.info ?: return false
    val left = info.repelLeft.toInt()
    if (left <= 0) return false
    characterStore.setRepel(charId, left - 1, if (left > 1) info.repelItemId.toInt() else 0)
    if (left == 1) session.send(notice("REPEL's effect wore off..."))
    return true
  }

  private fun leadLevel(charId: Long): Int =
      characterStore
          .getCharacter(charId)
          ?.pokemon
          ?.sortedBy { it.containerSlot }
          ?.firstOrNull { it.hp > 0 }
          ?.level
          ?.toInt() ?: 0

  private fun isLandEncounterTile(behavior: TileBehavior): Boolean =
      behavior == TileBehavior.TALL_GRASS || behavior == TileBehavior.LONG_GRASS

  private fun hasUsablePartyMon(charId: Long): Boolean =
      characterStore.getCharacter(charId)?.pokemon?.any { it.hp > 0 } ?: false

  private fun rollsEncounter(table: WildEncounterTable): Boolean {
    val chance = (table.encounterRate * ENCOUNTER_RATE_SCALE).coerceAtMost(ENCOUNTER_ROLL_MAX)
    return random.nextInt(ENCOUNTER_ROLL_MAX) < chance
  }

  /** Picks a slot weighted by its encounter rate. */
  private fun pickSlot(table: WildEncounterTable): WildEncounterSlot? {
    val total = table.slots.sumOf { it.weight }
    if (total <= 0) return null
    var roll = random.nextInt(total)
    for (slot in table.slots) {
      roll -= slot.weight
      if (roll < 0) return slot
    }
    return table.slots.lastOrNull()
  }
}
