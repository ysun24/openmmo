package de.fiereu.openmmo.server.game.battle

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A move-learn prompt waiting for its answer, kept after the battle ends. A trainer battle can
 * raise one monster past a level more than once, and an evolution can offer moves too, so these are
 * held per monster rather than per player.
 */
data class PendingMoveLearn(
    val charId: Long,
    val entityId: Long,
    val offered: List<Short>,
)

/** The open move-learn prompts, shared by battles and evolutions. */
@Singleton
class MoveLearnPrompts @Inject constructor() {
  private val pending = ConcurrentHashMap<Long, PendingMoveLearn>()

  fun offer(charId: Long, entityId: Long, offered: List<Short>) {
    pending[entityId] = PendingMoveLearn(charId, entityId, offered)
  }

  fun take(entityId: Long, charId: Long): PendingMoveLearn? {
    val p = pending[entityId] ?: return null
    if (p.charId != charId) return null
    pending.remove(entityId)
    return p
  }

  fun forget(charId: Long) {
    pending.values.removeIf { it.charId == charId }
  }
}
