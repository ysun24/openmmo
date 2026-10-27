package de.fiereu.openmmo.server.game.progression

import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.items.generated.Items
import kotlin.random.Random

/** The decomp's FRIENDSHIP_EVENT_* (include/constants/pokemon.h). */
enum class FriendshipEvent(val low: Int, val mid: Int, val high: Int) {
  GROW_LEVEL(5, 3, 2),
  VITAMIN(5, 3, 2),
  BATTLE_ITEM(1, 1, 0),
  LEAGUE_BATTLE(3, 2, 1),
  LEARN_TMHM(1, 1, 0),
  WALKING(1, 1, 1),
  FAINT_SMALL(-1, -1, -1),
  FAINT_FIELD_PSN(-5, -5, -10),
  FAINT_LARGE(-5, -5, -10),
  /**
   * Not an AdjustFriendship event: the EV-lowering berries' item effect
   * (EV_BERRY_FRIENDSHIP_CHANGE).
   */
  EV_BERRY(10, 5, 2),
}

/**
 * Gen 3 friendship (src/pokemon.c AdjustFriendship and the item-effect friendship bytes). The one
 * place friendship changes: battles (level up, faint), Rare Candy, vitamins, EV berries, TMs and
 * walking (every 128 steps, 50%). Soothe Bell adds half, a Luxury Ball one more point.
 */
object Friendship {
  const val MAX = 255
  const val EVOLVE = 220
  /**
   * Steps between walking friendship checks (field_control_avatar.c UpdateFriendshipStepCounter).
   */
  const val WALK_STEPS = 128

  fun delta(friendship: Int, event: FriendshipEvent): Int =
      when {
        friendship > 199 -> event.high
        friendship > 99 -> event.mid
        else -> event.low
      }

  /**
   * [mon] after [event]. [items] resolves the held item (Soothe Bell) and the ball; [random]
   * decides the walking event's 50% gate. Eggs never change.
   */
  fun adjust(
      mon: Pokemon,
      event: FriendshipEvent,
      items: ItemRegistry? = null,
      random: Random = Random.Default,
      luxuryBall: Boolean = false,
  ): Pokemon {
    if (mon.isEgg) return mon
    if (event == FriendshipEvent.WALKING && random.nextBoolean()) return mon
    return mon.copy(friendship = adjusted(mon.friendship, event, soothed(mon, items), luxuryBall))
  }

  fun adjusted(
      friendship: Int,
      event: FriendshipEvent,
      sootheBell: Boolean,
      luxuryBall: Boolean
  ): Int {
    var mod = delta(friendship, event)
    if (mod > 0 && sootheBell) mod = 150 * mod / 100
    var f = friendship + mod
    if (mod > 0 && luxuryBall) f++
    return f.coerceIn(0, MAX)
  }

  private fun soothed(mon: Pokemon, items: ItemRegistry?): Boolean =
      mon.heldItem != 0 && items?.get(mon.heldItem) == Items.SOOTHE_BELL
}
