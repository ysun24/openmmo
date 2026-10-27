package de.fiereu.openmmo.server.game.services

import de.fiereu.network.SessionContext
import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.enums.Ability
import de.fiereu.openmmo.common.enums.EncounterMethod
import de.fiereu.openmmo.common.enums.MapType
import de.fiereu.openmmo.common.enums.PokemonType
import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.common.enums.TileBehavior
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.maps.MapDef
import de.fiereu.openmmo.maps.WildEncounterSlot
import de.fiereu.openmmo.maps.WildEncounterTable
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import de.fiereu.openmmo.server.game.battle.BattleTerrain
import de.fiereu.openmmo.server.game.storage.CharacterStore
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

private val log = KotlinLogging.logger {}

// The GBA rolls an encounter out of 2880 (MAX_ENCOUNTER_RATE) with the table rate scaled by 16.
internal const val ENCOUNTER_ROLL_MAX = 2880
private const val ENCOUNTER_RATE_SCALE = 16

/** Per-player step state the GBA keeps in sWildEncounterData. */
private class StepState {
  var prevBehavior: TileBehavior? = null
  var stepsSinceLastEncounter = 0
  var encounterRateBuff = 0
}

/**
 * Wild encounters as the GBA rolls them (src/wild_encounter.c): land tiles (grass, cave floors,
 * deep sand, ash grass, FireRed's land-typed tiles) roll the LAND table, surfable water rolls the
 * WATER table. A step onto a different kind of tile only gets a check 60% of the time
 * (AllowWildCheckOnNewMetatile); the table rate is scaled by 16 against 2880 and modified by the
 * lead's Stench / Illuminate (and in Emerald White Smoke, Arena Trap) and a held Cleanse Tag.
 * FireRed adds its per-map step cooldown and the rate buff that grows with every miss. Slots use
 * the decomp's slot percentages; Emerald's Static / Magnet Pull pull an Electric / Steel slot half
 * the time, Hustle / Vital Spirit / Pressure push the level to the slot's maximum half the time and
 * Keen Eye / Intimidate skip weak encounters half the time. The repel skips monsters below the
 * lead's level.
 */
@Singleton
class EncounterService
@Inject
constructor(
    private val characterStore: CharacterStore,
    private val battleService: BattleService,
    private val species: SpeciesRegistry = SpeciesRegistry(),
    private val items: ItemRegistry = ItemRegistry(),
) {

  /** Tests pass a seeded source. */
  internal var random: Random = Random.Default

  private val steps = ConcurrentHashMap<Long, StepState>()

  /** Called after a completed step. Starts a wild battle if the tile and roll call for one. */
  fun onStep(session: SessionContext, charId: Long, map: MapDef, x: Int, y: Int) {
    if (battleService.inBattle(charId)) return
    val repelled = tickRepel(session, charId)
    val tile = map.tileAt(x, y) ?: return
    val state = steps.getOrPut(charId) { StepState() }
    val prev = state.prevBehavior
    state.prevBehavior = tile.behavior
    val method =
        when {
          tile.behavior.hasLandEncounters -> EncounterMethod.LAND
          tile.behavior.isSurfableWater -> EncounterMethod.WATER
          else -> return
        }
    val table = map.encounterTable(method) ?: return
    val lead = leadMon(charId) ?: return
    val kanto = map.regionId.toInt() == Region.KANTO.wireValue.toInt()
    if (kanto && !cooldownAllows(state, table, lead)) return
    if (prev != tile.behavior && random.nextInt(100) >= 60) return
    if (!rollsEncounter(table, lead, kanto, state)) {
      if (kanto)
          state.encounterRateBuff =
              if (repelled) 0 else state.encounterRateBuff + table.encounterRate
      return
    }
    val slot = pickSlot(table, lead, kanto) ?: return
    val level = chooseLevel(slot, lead, kanto)
    // The GBA repel: a wild monster below the lead's level does not appear.
    if (repelled && level < lead.level) return
    if (!kanto && !abilityAllowsEncounter(lead, level)) return
    state.stepsSinceLastEncounter = 0
    state.encounterRateBuff = 0
    val terrain = terrainFor(map, tile.behavior)
    log.info {
      "Wild encounter for char=$charId at ($x, $y) [$method/$terrain]: species ${slot.speciesId} level $level"
    }
    battleService.startWildBattle(
        session,
        slot.speciesId,
        level,
        terrain = terrain,
        underwater = map.mapType == MapType.UNDERWATER)
  }

  /** Forget a player's step state (logout). */
  fun forget(charId: Long) {
    steps.remove(charId)
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

  private fun leadMon(charId: Long): Pokemon? =
      characterStore
          .getCharacter(charId)
          ?.pokemon
          ?.filter { it.container == de.fiereu.openmmo.common.enums.PokemonContainer.PARTY }
          ?.sortedBy { it.containerSlot }
          ?.firstOrNull { it.hp > 0 && !it.isEgg }

  private fun abilityOf(mon: Pokemon): Ability {
    val def = species.get(mon.dexId) ?: return Ability.NONE
    return if (def.ability2 != Ability.NONE && (mon.seed and 1) == 1) def.ability2 else def.ability1
  }

  private fun holdsCleanseTag(mon: Pokemon): Boolean =
      mon.heldItem != 0 && items.get(mon.heldItem) == Items.CLEANSE_TAG

  /** DoWildEncounterRateTest / WildEncounterCheck. */
  internal fun encounterChance(
      table: WildEncounterTable,
      lead: Pokemon,
      kanto: Boolean,
      buff: Int
  ): Int {
    var rate = table.encounterRate * ENCOUNTER_RATE_SCALE
    if (kanto) rate += buff * 16 / 200
    if (holdsCleanseTag(lead)) rate = rate * 2 / 3
    when (abilityOf(lead)) {
      Ability.STENCH -> rate /= 2
      Ability.ILLUMINATE -> rate *= 2
      Ability.WHITE_SMOKE -> if (!kanto) rate /= 2
      Ability.ARENA_TRAP -> if (!kanto) rate *= 2
      else -> Unit
    }
    return rate.coerceAtMost(ENCOUNTER_ROLL_MAX)
  }

  private fun rollsEncounter(
      table: WildEncounterTable,
      lead: Pokemon,
      kanto: Boolean,
      state: StepState,
  ): Boolean =
      random.nextInt(ENCOUNTER_ROLL_MAX) <
          encounterChance(table, lead, kanto, state.encounterRateBuff)

  /** FireRed's HandleWildEncounterCooldown: a short no-encounter window after each battle. */
  private fun cooldownAllows(state: StepState, table: WildEncounterTable, lead: Pokemon): Boolean {
    val rate = table.encounterRate
    var minSteps = (if (rate >= 80) 0 else if (rate < 10) 8 else 8 - rate / 10) * 256
    var encRate = 5 * 256
    if (holdsCleanseTag(lead)) {
      minSteps += minSteps / 3
      encRate -= encRate / 3
    }
    when (abilityOf(lead)) {
      Ability.STENCH -> {
        minSteps *= 2
        encRate /= 2
      }
      Ability.ILLUMINATE -> {
        minSteps /= 2
        encRate *= 2
      }
      else -> Unit
    }
    minSteps /= 256
    encRate /= 256
    if (state.stepsSinceLastEncounter >= minSteps) return true
    state.stepsSinceLastEncounter++
    return random.nextInt(100) < encRate
  }

  /** Picks a slot by the decomp's per-slot percentages (weights). */
  internal fun pickSlot(
      table: WildEncounterTable,
      lead: Pokemon?,
      kanto: Boolean
  ): WildEncounterSlot? {
    // Emerald's TryGetAbilityInfluencedWildMonIndex: half the time a matching type's slot.
    if (!kanto && lead != null && table.method == EncounterMethod.LAND) {
      val forced =
          when (abilityOf(lead)) {
            Ability.MAGNET_PULL -> PokemonType.STEEL
            Ability.STATIC -> PokemonType.ELECTRIC
            else -> null
          }
      if (forced != null && random.nextBoolean()) {
        val matching = table.slots.filter { species.get(it.speciesId)?.hasType(forced) == true }
        if (matching.isNotEmpty()) return matching[random.nextInt(matching.size)]
      }
    }
    val total = table.slots.sumOf { it.weight }
    if (total <= 0) return null
    var roll = random.nextInt(total)
    for (slot in table.slots) {
      roll -= slot.weight
      if (roll < 0) return slot
    }
    return table.slots.lastOrNull()
  }

  /** ChooseWildMonLevel: uniform in the slot's range, Emerald's level abilities push to the top. */
  internal fun chooseLevel(slot: WildEncounterSlot, lead: Pokemon?, kanto: Boolean): Int {
    val min = minOf(slot.minLevel, slot.maxLevel)
    val max = maxOf(slot.minLevel, slot.maxLevel)
    val rolled = min + random.nextInt(max - min + 1)
    if (!kanto &&
        lead != null &&
        abilityOf(lead) in setOf(Ability.HUSTLE, Ability.VITAL_SPIRIT, Ability.PRESSURE) &&
        random.nextBoolean()) {
      return max
    }
    return rolled
  }

  /**
   * Emerald's IsAbilityAllowingEncounter: Keen Eye / Intimidate skip weak monsters half the time.
   */
  private fun abilityAllowsEncounter(lead: Pokemon, level: Int): Boolean {
    if (abilityOf(lead) !in setOf(Ability.KEEN_EYE, Ability.INTIMIDATE)) return true
    val leadLevel = lead.level.toInt()
    return !(leadLevel > 5 && level <= leadLevel - 5 && random.nextInt(2) == 0)
  }

  companion object {
    /** BattleSetup_GetEnvironmentId: the background for a battle started on [behavior] in [map]. */
    fun terrainFor(map: MapDef?, behavior: TileBehavior?): String {
      when (behavior) {
        TileBehavior.TALL_GRASS -> return BattleTerrain.GRASS
        TileBehavior.LONG_GRASS -> return BattleTerrain.LONG_GRASS
        TileBehavior.SAND,
        TileBehavior.DEEP_SAND -> return BattleTerrain.SAND
        else -> Unit
      }
      when (map?.mapType) {
        MapType.UNDERGROUND ->
            return when {
              behavior == TileBehavior.INDOOR_ENCOUNTER -> BattleTerrain.BUILDING
              behavior?.isSurfableWater == true -> BattleTerrain.POND
              else -> BattleTerrain.CAVE
            }
        MapType.INSIDE,
        MapType.SECRET_BASE -> return BattleTerrain.BUILDING
        MapType.UNDERWATER -> return BattleTerrain.UNDERWATER
        else -> Unit
      }
      return when (behavior) {
        TileBehavior.DEEP_WATER -> BattleTerrain.WATER
        TileBehavior.POND_WATER -> BattleTerrain.POND
        TileBehavior.MOUNTAIN -> BattleTerrain.ROCK
        TileBehavior.CAVE -> BattleTerrain.CAVE
        TileBehavior.ASH_GRASS -> BattleTerrain.GRASS
        else -> BattleTerrain.PLAIN
      }
    }
  }
}
