package de.fiereu.openmmo.server.game.battle

/** A battle position: [side] 0 is the viewing player's side, 1 the opposing side. */
data class BattlerRef(val side: Int, val pos: Int) {
  val foeSide: Int
    get() = 1 - side
}

/** What one battle position does this turn. */
sealed interface BattleChoice {
  val side: Int
  val pos: Int

  /** [target] null lets the engine pick (the single foe, or a random one in doubles). */
  data class UseMove(
      override val side: Int,
      override val pos: Int,
      val moveId: Int,
      val target: BattlerRef? = null,
  ) : BattleChoice

  data class Switch(override val side: Int, override val pos: Int, val partyIndex: Int) :
      BattleChoice

  /** The turn went to a bag item (resolved before the turn) or a failed ball. */
  data class Pass(override val side: Int, override val pos: Int) : BattleChoice
}

sealed interface BattleEvent {
  data class MoveUsed(val attackerId: Long, val moveId: Short, val moveSlot: Int, val ppLeft: Int) :
      BattleEvent

  /** A move that reached no target of its own, so its message lands on the opponent. */
  sealed interface MoveWithoutTarget : BattleEvent {
    val attackerId: Long
    val moveId: Short
  }

  data class MoveMissed(
      override val attackerId: Long,
      override val moveId: Short,
      val targetId: Long = 0,
  ) : MoveWithoutTarget

  data class MoveFailed(
      override val attackerId: Long,
      override val moveId: Short,
      val targetId: Long = 0,
  ) : MoveWithoutTarget

  data class DamageDealt(
      val targetId: Long,
      val newHp: Int,
      val crit: Boolean,
      val effectiveness: Int,
  ) : BattleEvent

  data class StageChanged(
      val targetId: Long,
      val stat: BattleStat,
      val stage: Int,
      val effectiveValue: Int,
      val delta: Int,
      val failed: Boolean,
  ) : BattleEvent

  data class Fainted(val targetId: Long) : BattleEvent

  // --- OpenMMO extension events: sent in the battle-extension stream, see BattlePacketEmitter --

  /** A battle text line. */
  data class Message(val text: String, val targetId: Long = 0) : BattleEvent

  /** Hp moved outside a move's own damage: poison, burn, recoil, drain, Leftovers, items... */
  data class HpChanged(val targetId: Long, val newHp: Int, val reason: String) : BattleEvent

  /** A major status was set ([status] != 0) or cured ([status] == 0). */
  data class StatusChanged(val targetId: Long, val status: Int, val code: String?) : BattleEvent

  /** A volatile effect started or ended: confusion, flinch, seeded, substitute... */
  data class VolatileChanged(val targetId: Long, val kind: String, val on: Boolean) : BattleEvent

  /** An ability announced itself (Intimidate, Static, Levitate...). */
  data class AbilityShown(val holderId: Long, val ability: String) : BattleEvent

  /** A held item did something; [consumed] when it is used up. */
  data class ItemActivated(val holderId: Long, val itemId: Int, val consumed: Boolean) :
      BattleEvent

  data class WeatherChanged(val weather: Weather) : BattleEvent

  /** The engine brought a monster out (Roar, Whirlwind, a turn's switch, Baton Pass). */
  data class Switched(
      val side: Int,
      val pos: Int,
      val oldSlot: Int,
      val newSlot: Int,
      val entityId: Long,
  ) : BattleEvent

  /** A wild monster or the player left the battle (Teleport, Roar on a wild, run). */
  data class Fled(val side: Int) : BattleEvent

  /** A move animation without a target hp change (status moves, self moves). */
  data class Animation(val attackerId: Long, val moveId: Int, val targetId: Long) : BattleEvent
}
