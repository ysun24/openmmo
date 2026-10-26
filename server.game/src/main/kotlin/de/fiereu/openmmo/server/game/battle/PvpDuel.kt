package de.fiereu.openmmo.server.game.battle

import de.fiereu.openmmo.net.game.packets.battle.BattleActionSelectPacket
import kotlinx.coroutines.sync.Mutex

/**
 * A battle between two players. Each player gets a [BattleInstance] of their own perspective:
 * [first]'s party is [second]'s opponent and the other way round, and both share the same
 * [BattleMonState] objects, so damage dealt in one view is the state of the other. Every turn waits
 * for both players' actions before the [TurnEngine] resolves it.
 */
class PvpDuel(val first: BattleInstance, val second: BattleInstance) {
  /** Both players' packets arrive on their own threads. */
  val mutex = Mutex()

  /** The action each side picked this turn, keyed by character id. */
  val actions = mutableMapOf<Long, BattleActionSelectPacket>()

  /** Sides that owe a replacement for a fainted monster before the next turn. */
  val forced = mutableSetOf<Long>()

  var finished = false

  fun rival(of: BattleInstance): BattleInstance = if (of === first) second else first

  fun sides(): List<BattleInstance> = listOf(first, second)
}
