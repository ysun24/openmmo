package de.fiereu.openmmo.server.game.battle

import de.fiereu.openmmo.net.game.packets.battle.DuelRules
import kotlinx.coroutines.sync.Mutex

/**
 * A battle between two players. Each player gets a [BattleInstance] of their own perspective:
 * [first]'s party is [second]'s opponent and the other way round. Both share the same
 * [BattleMonState] objects, the same field and the same side states (first.own is second.foe), so
 * damage dealt in one view is the state of the other. Every turn waits for both players' choices
 * (one per battle position in a 2v2 duel) before the [TurnEngine] resolves it.
 */
class PvpDuel(
    val first: BattleInstance,
    val second: BattleInstance,
    val rules: DuelRules = DuelRules.DEFAULT,
) {
  /** Both players' packets arrive on their own threads. */
  val mutex = Mutex()

  /** The choices each side picked this turn, in that player's own perspective (side 0 = self). */
  val choices = mutableMapOf<Long, MutableMap<Int, BattleChoice>>()

  /** Battle positions that owe a replacement for a fainted monster, per character id. */
  val owed = mutableMapOf<Long, MutableSet<Int>>()

  /** Characters that owe at least one replacement before the next turn. */
  val forced: Set<Long>
    get() = owed.filterValues { it.isNotEmpty() }.keys

  /** Turn timeouts per character id. At [DuelRules.MAX_TIMEOUTS] that player forfeits. */
  val timeouts = mutableMapOf<Long, Int>()

  /** Bumped whenever a turn timer is armed, so a stale timeout does nothing. */
  var timerGeneration = 0

  /** Cancels the running turn timer. */
  var cancelTimer: () -> Unit = {}

  var finished = false

  fun rival(of: BattleInstance): BattleInstance = if (of === first) second else first

  fun sides(): List<BattleInstance> = listOf(first, second)

  /** True once [side] picked for every live position. */
  fun complete(side: BattleInstance): Boolean {
    val picked = choices[side.charId].orEmpty()
    return side.own.actives.indices.all { pos ->
      pos in picked || side.party.getOrNull(side.own.actives[pos])?.fainted != false
    }
  }
}
