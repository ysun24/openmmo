package de.fiereu.openmmo.server.game.battle

import de.fiereu.network.SessionContext
import de.fiereu.openmmo.server.game.world.interest.BattleInterestKey
import de.fiereu.openmmo.trainer.TrainerDef
import kotlinx.coroutines.CompletableDeferred

enum class BattleResult {
  VICTORY,
  DEFEAT,
  FLED,
  CAUGHT,
  DISCONNECTED,
  FAILED,
}

/** Gen 3 weather. Ability weather lasts until replaced; move weather lasts five turns. */
enum class Weather(val code: String) {
  NONE("none"),
  RAIN("rain"),
  SUN("sun"),
  SAND("sand"),
  HAIL("hail"),
}

/** Field-wide state, shared by both views of a player-vs-player battle. */
class BattleField {
  var weather: Weather = Weather.NONE
  /** Turns left, 0 = permanent (from an ability). */
  var weatherTurns = 0
  var mudSport = false
  var waterSport = false
  /** Timer Ball counts battle turns. */
  var turnCounter = 0
}

/**
 * One side of the field: which party slots are active (one entry per battle position) and the
 * screens and hazards on it. [actives] entries are -1 while a position is empty.
 */
class BattleSideState(val actives: MutableList<Int>) {
  var reflectTurns = 0
  var lightScreenTurns = 0
  var mistTurns = 0
  var safeguardTurns = 0
  var spikes = 0
  /** Wish: hp to restore at the end of next turn per position, and its countdown. */
  val wish = mutableMapOf<Int, Pair<Int, Int>>()
  /** Future Sight / Doom Desire: per position, turns left, damage. */
  val futureSight = mutableMapOf<Int, Pair<Int, Int>>()
}

/** What kind of battle this is, beyond the two teams. A [trainer] owns the opposing side. */
data class BattleRules(
    val catchable: Boolean = true,
    val escapable: Boolean = true,
    val trainer: TrainerDef? = null,
    /** Monsters active per side: 1 (single) or 2 (double). */
    val format: Int = 1,
    /** The battle background, see [BattleTerrain]. */
    val terrain: String = BattleTerrain.GRASS,
    /** "gym", "elite", "champion", "trainer", "wild" or "rival"; picks the music. */
    val musicKey: String? = null,
    /** True when the encounter happened under water (Dive Ball). */
    val underwater: Boolean = false,
)

/** Battle background keys, matching the exported backgrounds (graphics/battle_environment). */
object BattleTerrain {
  const val GRASS = "tall_grass"
  const val LONG_GRASS = "long_grass"
  const val SAND = "sand"
  const val UNDERWATER = "underwater"
  const val WATER = "water"
  const val POND = "pond_water"
  const val ROCK = "rock"
  const val CAVE = "cave"
  const val BUILDING = "building"
  const val PLAIN = "plain"
  const val STADIUM = "stadium"
}

/** One running battle. A wild encounter is the case where [opponent] holds a single monster. */
class BattleInstance(
    val battleId: Long,
    val charId: Long,
    val session: SessionContext,
    val party: List<BattleMonState>,
    val opponent: List<BattleMonState>,
    val rng: BattleRng,
    val catchable: Boolean = true,
    val escapable: Boolean = true,
    /** The trainer who owns [opponent], or null for a wild encounter. */
    val trainer: TrainerDef? = null,
    val format: Int = 1,
    /** This player's side, shared with the rival's [foe] in a player-vs-player battle. */
    val own: BattleSideState = BattleSideState(MutableList(format) { it }),
    val foe: BattleSideState = BattleSideState(MutableList(format) { it }),
    val field: BattleField = BattleField(),
    val terrain: String = BattleTerrain.GRASS,
    val musicKey: String? = null,
    val underwater: Boolean = false,
) {
  val key: BattleInterestKey = BattleInterestKey(battleId)
  var turn: Int = 1

  /** The first battle position's party slot. Kept for single battles and older callers. */
  var activeSlot: Int
    get() = own.actives[0]
    set(value) {
      own.actives[0] = value
    }

  var opponentSlot: Int
    get() = foe.actives[0]
    set(value) {
      foe.actives[0] = value
    }

  // Which slots have been sent out as active. A monster's first appearance carries its full block,
  // a return only its active detail.
  val seenActive: MutableSet<Int> = mutableSetOf(0)
  val opponentSeen: MutableSet<Int> = mutableSetOf(0)

  /** Completes when the battle leaves the registry. */
  val completion = CompletableDeferred<BattleResult>()

  /** Set when the other side is a player rather than the AI, see [PvpDuel]. */
  var pvp: PvpDuel? = null

  /** r5-frontier: the battle facility's rules, see [FacilityHooks]. */
  var facility: FacilityHooks? = null

  /** r5-frontier: true for every facility battle (with or without hooks); nothing is persisted. */
  var facilityBattle: Boolean = false

  /**
   * r5-frontier: a Battle Tower multi battle with another player. Party slot -> the character that
   * controls it, and that character's session, so each player picks for their own position only.
   */
  var multiOwners: Map<Int, Long> = emptyMap()
  var multiSessions: Map<Long, SessionContext> = emptyMap()

  /**
   * The player's name, the OT their own monsters carry; a monster with another OT is traded and
   * obeys only up to [obedienceLevel] (battle_util.c IsMonDisobedient). Empty: everything obeys.
   */
  var trainerName: String = ""

  /** 10, 30/50/70 with badges 2/4/6, 100 (everything obeys) with badge 8: see BadgeService. */
  var obedienceLevel: Int = 100

  /** Result held until the client confirms that its battle-to-map transition has finished. */
  var pendingResult: BattleResult? = null

  /** Actions picked so far this turn, by battle position (doubles wait for both). */
  val pendingChoices = sortedMapOf<Int, BattleChoice>()

  /** Positions that owe a replacement for a fainted monster. */
  val owedSwitches = sortedSetOf<Int>()

  /** Failed escape attempts, which raise the next one's odds. */
  var escapeAttempts = 0

  /** Trainer items not used yet. */
  val trainerItems: MutableList<String> = trainer?.items?.toMutableList() ?: mutableListOf()

  /** Prize money bonus collected by Pay Day. */
  var payDay = 0

  /** Party slots that took part in this battle, for the participation EXP split. */
  val participants: MutableSet<Int> = mutableSetOf()

  fun activeMon(): BattleMonState = party[activeSlot.coerceAtLeast(0)]

  fun opponentMon(): BattleMonState = opponent[opponentSlot.coerceAtLeast(0)]

  /** Monsters out on the player's side, by position (null for an empty position). */
  fun activeMons(): List<BattleMonState?> = own.actives.map { party.getOrNull(it) }

  fun opponentMons(): List<BattleMonState?> = foe.actives.map { opponent.getOrNull(it) }

  fun isPlayerSide(entityId: Long): Boolean = party.any { it.entityId == entityId }

  fun isDouble(): Boolean = format >= 2
}
