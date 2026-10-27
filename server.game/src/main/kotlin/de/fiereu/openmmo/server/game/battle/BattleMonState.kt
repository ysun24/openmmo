package de.fiereu.openmmo.server.game.battle

import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.PokemonMove
import de.fiereu.openmmo.common.StatusCondition
import de.fiereu.openmmo.common.enums.Ability
import de.fiereu.openmmo.common.enums.PokemonType
import de.fiereu.openmmo.net.game.packets.battle.BattleMonBlock
import de.fiereu.openmmo.net.game.packets.battle.BattleOpponentBlock
import de.fiereu.openmmo.pokemon.SpeciesDef
import java.util.EnumMap

/**
 * The Gen 3 "status2"/"status3"/disable-struct state of a battler: everything that lasts while the
 * monster stays in and is cleared when it switches out or the battle ends.
 */
class Volatiles {
  var confusionTurns = 0
  var flinched = false
  var focusEnergy = false
  var substituteHp = 0
  var protectedThisTurn = false
  var protectChain = 0
  var enduring = false
  var mustRecharge = false
  /** A two-turn move in its charge turn (Solar Beam, Fly, Dig, Dive, Razor Wind...). */
  var chargingMove: Int = 0
  /** "FLY", "DIG", "DIVE" while semi-invulnerable, null otherwise. */
  var semiInvulnerable: String? = null
  var rampageMove: Int = 0
  var rampageTurns = 0
  var trapTurns = 0
  var trapMove: Int = 0
  var trappedBy: Long = 0
  /** Leech Seed: the entity id the drained hp goes to. */
  var seededBy: Long = 0
  var disabledMove: Int = 0
  var disableTurns = 0
  var encoreMove: Int = 0
  var encoreTurns = 0
  var tauntTurns = 0
  var yawnTurns = 0
  var attractedTo: Long = 0
  var cursed = false
  var nightmare = false
  var foresight = false
  var lockOnTarget: Long = 0
  var lockOnTurns = 0
  var destinyBond = false
  var perishCount = 0
  var minimized = false
  var defenseCurl = false
  var raging = false
  var charged = false
  var flashFire = false
  var truantSkip = false
  var choiceMove: Int = 0
  var lastMove: Int = 0
  var furyCutter = 0
  var rolloutTurns = 0
  var stockpile = 0
  var ingrained = false
  var helpingHand = false
  var cantEscape = false
  var bideTurns = 0
  var bideDamage = 0
  var uproarTurns = 0
  /** Damage taken this turn and its category, for Counter / Mirror Coat / Revenge / Bide. */
  var physicalDamageTaken = 0
  var specialDamageTaken = 0
  var damagedBy: Long = 0
  /** True once the monster has acted in the battle since it came in (Fake Out). */
  var actedSinceSwitchIn = false
  var movedThisTurn = false
  var magicCoat = false
  var transformed = false
  var toxicCounter = 0
}

/**
 * One monster's live state inside a battle. [source] is the snapshot the battle started from.
 * [currentHp], the move PP and [status] are the live values written back when the battle ends.
 */
class BattleMonState(
    val entityId: Long,
    species: SpeciesDef,
    val partyIndex: Int?,
    // Both move on when a reward lands, so a second reward in the same battle builds on the first.
    var source: Pokemon,
    var stats: ComputedStats,
    val gender: Byte = 0,
) {
  /** The species; changes when the monster evolves mid-session or transforms. */
  var species: SpeciesDef = species

  var currentHp: Int = source.hp.toInt().coerceIn(0, stats.hp)
  val moves: MutableList<PokemonMove> =
      source.moves.map { PokemonMove(it.id, it.pp) }.toMutableList()

  /** Packed Gen 3 status1 (see [StatusCondition]); persisted after the battle. */
  var status: Int = source.status

  /** The held item's wire id, 0 for none. Consumed berries and stolen items move this. */
  var heldItem: Int = source.heldItem

  /** Gen 3 picks the ability from the personality's lowest bit when the species has two. */
  var ability: Ability =
      if (species.ability2 != Ability.NONE && (source.seed and 1) == 1) species.ability2
      else species.ability1

  /** Battle types (Conversion / Color Change rewrite them). */
  var types: MutableList<PokemonType> = mutableListOf(species.type1, species.type2)

  var volatile = Volatiles()

  /** The party monsters that fought this one while it was in, for the EXP split. */
  val foughtBy: MutableSet<Long> = mutableSetOf()

  private val stages = EnumMap<BattleStat, Int>(BattleStat::class.java)

  val level: Int
    get() = source.level.toInt()

  val fainted: Boolean
    get() = currentHp <= 0

  val maxHp: Int
    get() = stats.hp

  fun hasType(type: PokemonType): Boolean = type in types

  fun stage(stat: BattleStat): Int = stages[stat] ?: 0

  fun setStage(stat: BattleStat, value: Int) {
    stages[stat] = value.coerceIn(StatStages.MIN, StatStages.MAX)
  }

  fun clearStages() = stages.clear()

  /** Clamp to the stage limits and return the delta that was actually applied. */
  fun changeStage(stat: BattleStat, delta: Int): Int {
    val old = stage(stat)
    val new = (old + delta).coerceIn(StatStages.MIN, StatStages.MAX)
    stages[stat] = new
    return new - old
  }

  fun unstaged(stat: BattleStat): Int =
      when (stat) {
        BattleStat.ATTACK -> stats.atk
        BattleStat.DEFENSE -> stats.def
        BattleStat.SP_ATTACK -> stats.spAtk
        BattleStat.SP_DEFENSE -> stats.spDef
        BattleStat.SPEED -> stats.spd
        BattleStat.ACCURACY,
        BattleStat.EVASION -> error("$this has no base stat to stage")
      }

  fun effective(stat: BattleStat): Int = StatStages.scaleStat(unstaged(stat), stage(stat))

  // --- status -----------------------------------------------------------------------------------

  val asleep: Boolean
    get() = status and StatusCondition.SLEEP_MASK != 0

  val frozen: Boolean
    get() = status and StatusCondition.FREEZE != 0

  val paralyzed: Boolean
    get() = status and StatusCondition.PARALYSIS != 0

  val burned: Boolean
    get() = status and StatusCondition.BURN != 0

  val poisoned: Boolean
    get() = status and (StatusCondition.POISON or StatusCondition.TOXIC) != 0

  val badlyPoisoned: Boolean
    get() = status and StatusCondition.TOXIC != 0

  val hasStatus: Boolean
    get() = status != 0

  val confused: Boolean
    get() = volatile.confusionTurns > 0

  /** What switching out (or the battle ending) leaves behind: stages and volatiles go. */
  fun resetOnSwitchOut() {
    clearStages()
    volatile = Volatiles()
    types = mutableListOf(species.type1, species.type2)
  }

  /** The stored monster with this battle's hp, pp and status written back. */
  fun persisted(): Pokemon =
      source.copy(
          hp = currentHp.coerceAtLeast(0).toShort(),
          moves = moves.map { PokemonMove(it.id, it.pp) },
          status = if (fainted) 0 else status,
          heldItem = heldItem,
      )

  fun toOpponentBlock(slot: Int): BattleOpponentBlock =
      BattleOpponentBlock(
          slot = slot,
          revealed = true,
          entityId = entityId,
          species = species.id.toShort(),
          level = source.level,
          gender = gender,
          maxHp = stats.hp.toShort(),
          currentHp = currentHp.toShort(),
      )

  fun toBlock(slot: Int, movesPresent: Boolean): BattleMonBlock =
      BattleMonBlock(
          slot = slot,
          entityId = entityId,
          species = species.id.toShort(),
          level = source.level,
          gender = gender,
          abilityId = ability.ordinal.toShort(),
          maxHp = stats.hp.toShort(),
          currentHp = currentHp.toShort(),
          movesPresent = movesPresent,
          moveIds = List(BattleMonBlock.MOVE_SLOTS) { moves.getOrNull(it)?.id ?: 0 },
      )
}
