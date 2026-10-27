package de.fiereu.openmmo.server.game.frontier

import de.fiereu.openmmo.common.MAX_MOVE_SLOTS
import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.PokemonMove
import de.fiereu.openmmo.common.enums.EVs
import de.fiereu.openmmo.common.enums.IVs
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.moves.MoveRegistry
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import de.fiereu.openmmo.server.game.battle.ExpCurves
import de.fiereu.openmmo.server.game.battle.ItemNames
import de.fiereu.openmmo.server.game.battle.StatCalculator
import de.fiereu.openmmo.server.game.storage.EntityIdService
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Builds facility monsters the decomp's ways: CreateMonWithEVSpreadNatureOTID for frontier sets,
 * CreateFrontierBrainPokemon for the brains, CreateBattleTowerMon for Trainer Hill / Trainer Tower
 * entries, and the level fix-up the facilities apply to the player's own monsters.
 */
@Singleton
class FrontierMonFactory
@Inject
constructor(
    private val species: SpeciesRegistry,
    private val moves: MoveRegistry,
    private val items: ItemRegistry,
    private val entityIds: EntityIdService,
) {
  /** A personality whose nature (personality % 25) is [nature], with a random ability bit. */
  fun personalityFor(nature: Int, rng: Random): Int {
    while (true) {
      val p = rng.nextInt()
      if (((p.toLong() and 0xFFFFFFFFL) % 25).toInt() == nature) return p
    }
  }

  /** A frontier set at [level] with [fixedIv] in every stat. */
  fun facilityMon(
      set: FacilityMon,
      level: Int,
      fixedIv: Int,
      rng: Random,
      /** Factory rentals and opponents: Return becomes Frustration and friendship is 0. */
      avoidReturn: Boolean = false,
      personality: Int? = null,
  ): Pokemon {
    val evs = FrontierRules.evSpread(set.evSpread)
    var friendship = if (avoidReturn) 0 else FrontierRules.MAX_FRIENDSHIP
    val moveIds =
        set.moves.map {
          if (avoidReturn && it == FrontierRules.MOVE_RETURN) FrontierRules.MOVE_FRUSTRATION else it
        }
    if (!avoidReturn && FrontierRules.MOVE_FRUSTRATION in moveIds) friendship = 0
    return build(
        set.species,
        level,
        personality ?: personalityFor(set.nature, rng),
        IntArray(6) { fixedIv },
        evs,
        moveIds,
        set.item,
        friendship)
  }

  /** A Frontier Brain's monster (fixed IVs and EVs per stat, never shiny). */
  fun brainMon(mon: BrainMon, level: Int, rng: Random): Pokemon =
      build(
          mon.species,
          level,
          personalityFor(mon.nature, rng),
          IntArray(6) { mon.fixedIv },
          mon.evs.toIntArray(),
          mon.moves,
          mon.item,
          if (FrontierRules.MOVE_FRUSTRATION in mon.moves) 0 else FrontierRules.MAX_FRIENDSHIP)

  /** A BattleTowerPokemon entry (Trainer Hill / Trainer Tower) set to [level]. */
  fun towerMon(mon: TowerMon, level: Int): Pokemon {
    // The ability bit lives in the personality's lowest bit here, so carry abilityNum over.
    val p = (mon.personality and 1.inv()) or (mon.abilityNum and 1)
    return build(
        mon.species,
        level,
        // Keep the nature (personality % 25) while the low bit follows abilityNum.
        if (((p.toLong() and 0xFFFFFFFFL) % 25).toInt() ==
            ((mon.personality.toLong() and 0xFFFFFFFFL) % 25).toInt())
            p
        else mon.personality,
        mon.ivs.toIntArray(),
        mon.evs.toIntArray(),
        mon.moves,
        mon.item,
        mon.friendship,
        nickname = mon.nickname)
  }

  /** A wild monster from a Pike / Pyramid table with its fixed moves. */
  fun wildMon(dex: Int, level: Int, moveIds: List<Int>, ability: Int, rng: Random): Pokemon {
    val ivs = IntArray(6) { rng.nextInt(32) }
    var p = rng.nextInt()
    if (ability >= 0) p = (p and 1.inv()) or (ability and 1)
    return build(dex, level, p, ivs, IntArray(6), moveIds, "NONE", 70)
  }

  /**
   * The player's monster as the facility fields it: same identity and moves, at [level] when the
   * facility sets one (Open Level keeps its level), full hp and pp.
   */
  fun entrant(mon: Pokemon, level: Int? = null): Pokemon {
    val def = species.get(mon.dexId) ?: return mon
    val lvl = level ?: mon.level.toInt()
    val leveled =
        mon.copy(
            level = lvl.toByte(),
            xp = ExpCurves.totalXpFor(def.growthRate, lvl),
            status = 0,
            moves =
                mon.moves.mapIndexed { i, m ->
                  PokemonMove(m.id, maxPp(m.id.toInt(), mon.ppBonuses, i))
                })
    return leveled.copy(hp = StatCalculator.computeAll(def, leveled).hp.toShort())
  }

  /**
   * Full hp and pp, no status: the heal between battles of Tower / Dome / Palace / Arena / Factory.
   */
  fun healed(mon: Pokemon): Pokemon {
    val def = species.get(mon.dexId) ?: return mon
    return mon.copy(
        hp = StatCalculator.computeAll(def, mon).hp.toShort(),
        status = 0,
        moves =
            mon.moves.mapIndexed { i, m ->
              PokemonMove(m.id, maxPp(m.id.toInt(), mon.ppBonuses, i))
            })
  }

  fun maxHp(mon: Pokemon): Int =
      species.get(mon.dexId)?.let { StatCalculator.computeAll(it, mon).hp } ?: mon.hp.toInt()

  private fun maxPp(moveId: Int, ppBonuses: Int, slot: Int): Byte {
    if (moveId == 0) return 0
    val base = moves.get(moveId)?.pp ?: 0
    val ups = (ppBonuses shr (slot.coerceAtLeast(0) * 2)) and 3
    return (base + base * ups / 5).toByte()
  }

  private fun build(
      dex: Int,
      level: Int,
      personality: Int,
      ivs: IntArray,
      evs: IntArray,
      moveIds: List<Int>,
      item: String,
      friendship: Int,
      nickname: String = "",
  ): Pokemon {
    val def = checkNotNull(species.get(dex)) { "no species $dex" }
    val iv =
        IVs().apply {
          hp = ivs[0].coerceIn(0, 31)
          atk = ivs[1].coerceIn(0, 31)
          this.def = ivs[2].coerceIn(0, 31)
          spd = ivs[3].coerceIn(0, 31)
          spAtk = ivs[4].coerceIn(0, 31)
          spDef = ivs[5].coerceIn(0, 31)
        }
    // Gen 3 allows 255 per stat; our EVs cap one stat at 252 (and all at 510).
    val ev =
        EVs().apply {
          var left = 510
          fun take(v: Int): Int = v.coerceIn(0, 252).coerceAtMost(left).also { left -= it }
          hp = take(evs[0])
          atk = take(evs[1])
          this.def = take(evs[2])
          spd = take(evs[3])
          spAtk = take(evs[4])
          spDef = take(evs[5])
        }
    val ids = moveIds.filter { it != 0 }.take(MAX_MOVE_SLOTS)
    val moveset =
        ids.map { PokemonMove(it.toShort(), (moves.get(it)?.pp ?: 0).toByte()) } +
            List(MAX_MOVE_SLOTS - ids.size) { PokemonMove(0, 0) }
    val mon =
        Pokemon(
            id = entityIds.newMonsterId(),
            ownerId = 0,
            container = PokemonContainer.PARTY,
            containerSlot = 0,
            dexId = dex,
            seed = personality,
            ot = "",
            nickname =
                nickname.takeIf { it.isNotBlank() && it.uppercase() != def.name.uppercase() } ?: "",
            level = level.toByte(),
            hp = 0,
            xp = ExpCurves.totalXpFor(def.growthRate, level),
            eVs = ev,
            iVs = iv,
            moves = moveset,
            isShiny = false,
            hasHiddenAbility = false,
            isAlpha = false,
            isSecret = false,
            isFatefulEncounter = false,
            isRaidEncounter = false,
            caughtAt = LocalDateTime.now(),
            friendship = friendship,
            heldItem = ItemNames.wireId(items, item),
        )
    return mon.copy(hp = StatCalculator.computeAll(def, mon).hp.toShort())
  }
}
