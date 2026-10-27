package de.fiereu.openmmo.server.game.battle

import de.fiereu.openmmo.common.Pokemon

/**
 * r5-frontier: what a battle facility changes about one battle. The Battle Frontier, the Battle
 * Tents, the Trainer Hill and the FRLG Trainer Tower run their battles on the normal engine with
 * these hooks; null on [BattleInstance.facility] keeps every rule as it was.
 *
 * Facility battles never write anything back to the stored party (no EXP, no prize money, no hp
 * or pp, no friendship, no white-out): the facility owns its team copy and gets the end state
 * back from [BattleService.startFacilityBattle].
 */
interface FacilityHooks {
  /** Battle Palace: the monsters pick their own moves, the player only watches. */
  val autoPlay: Boolean
    get() = false

  /** Battle Arena / Palace: no switching by choice (and the next monster comes out by itself). */
  val allowSwitch: Boolean
    get() = true

  /** Player-side positions the AI plays (the Battle Tower multi battle's NPC partner). */
  val aiPositions: Set<Int>
    get() = emptySet()

  /** Bag items in battle; only the Battle Pyramid's own bag allows any. */
  val allowItems: Boolean
    get() = false

  /** Called before each turn resolves. */
  fun beforeTurn(engine: TurnEngine, battle: BattleInstance) {}

  /**
   * Palace: the choice for one live position of either side, and an optional line shown first
   * ("X appears incapable of using its power!"). Null lets the normal choice stand.
   */
  fun autoChoice(
      engine: TurnEngine,
      battle: BattleInstance,
      ref: BattlerRef,
  ): Pair<BattleChoice, String?>? = null

  /** After each turn with its events; returns more events to play (Arena judgment, Palace lines). */
  fun afterTurn(
      engine: TurnEngine,
      battle: BattleInstance,
      events: List<BattleEvent>,
  ): List<BattleEvent> = emptyList()

  /** The Battle Pyramid bag: item id -> count usable in this battle, and its consumption. */
  fun bag(): Map<Int, Int> = emptyMap()

  fun consume(itemId: Int) {}
}

/** One facility battle: the teams as Pokemon, the opposing trainer and the rules. */
data class FacilityBattleSetup(
    val team: List<Pokemon>,
    val opponents: List<Pokemon>,
    val trainerName: String,
    /** TRAINER_PIC_ suffix (e.g. "YOUNGSTER", "ANABEL"). */
    val trainerPic: String,
    /** TRAINER_CLASS_ suffix. */
    val trainerClass: String,
    /** 1 single, 2 double. */
    val format: Int = 1,
    val hooks: FacilityHooks? = null,
    /** Party slots from here on are an NPC partner's (position 1 of a multi battle), -1 none. */
    val partnerFrom: Int = -1,
    /** "wild" for the Pike / Pyramid wild monsters. */
    val wild: Boolean = false,
    val musicKey: String = "trainer",
    val terrain: String = BattleTerrain.BUILDING,
    /** The line the trainer says before the battle, shown as the battle's first message. */
    val intro: String = "",
)

/** How a facility battle ended, and the facility team as it came out of it. */
data class FacilityBattleOutcome(val result: BattleResult, val team: List<Pokemon>)
