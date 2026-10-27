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

  /**
   * Called after a completed step. Starts a wild battle if the tile and roll call for one: land
   * tables on grass and encounter ground, the water table while [surfing] over water.
   */
  fun onStep(
      session: SessionContext,
      charId: Long,
      map: MapDef,
      x: Int,
      y: Int,
      surfing: Boolean = false,
  ) {
    if (battleService.inBattle(charId)) return
    val repelled = tickRepel(session, charId)
    val tile = map.tileAt(x, y) ?: return
    val method =
        when {
          surfing && tile.behavior.isSurfable -> EncounterMethod.WATER
          !surfing && isLandEncounterTile(tile.behavior) -> EncounterMethod.LAND
          else -> return
        }
    encounter(session, charId, map, method, repelled, force = false, x, y)
  }

  /**
   * An encounter that is not a step: ROCK SMASH (rock_smash_mons, special RockSmashWildEncounter)
   * and SWEET SCENT ([force]: always meets a monster when the tile has a table). True when a battle
   * started.
   */
  fun encounterNow(
      session: SessionContext,
      charId: Long,
      map: MapDef,
      method: EncounterMethod,
      force: Boolean,
  ): Boolean {
    if (battleService.inBattle(charId)) return false
    return encounter(session, charId, map, method, repelled = false, force = force, -1, -1)
  }

  private fun encounter(
      session: SessionContext,
      charId: Long,
      map: MapDef,
      method: EncounterMethod,
      repelled: Boolean,
      force: Boolean,
      x: Int,
      y: Int,
  ): Boolean {
    val table = map.encounterTable(method) ?: return false
    if (!hasUsablePartyMon(charId)) return false
    if (!force && !rollsEncounter(table)) return false
    val slot = pickSlot(table) ?: return false
    val level = random.nextInt(slot.minLevel, slot.maxLevel + 1)
    // The GBA repel: a wild monster below the lead's level does not appear.
    if (repelled && level < leadLevel(charId)) return false
    log.info {
      "Wild $method encounter for char=$charId at ($x, $y): species ${slot.speciesId} level $level"
    }
    battleService.startWildBattle(session, slot.speciesId, level)
    return true
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

  private fun isLandEncounterTile(behavior: TileBehavior): Boolean = behavior.isLandEncounter

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
