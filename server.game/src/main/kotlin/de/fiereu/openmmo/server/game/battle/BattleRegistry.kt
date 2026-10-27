package de.fiereu.openmmo.server.game.battle

import de.fiereu.network.SessionContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** The running battles, one per character. */
@Singleton
class BattleRegistry @Inject constructor() {

  private val byChar = ConcurrentHashMap<Long, BattleInstance>()
  private val ids = AtomicLong(1)

  fun create(
      charId: Long,
      session: SessionContext,
      party: List<BattleMonState>,
      opponent: List<BattleMonState>,
      rng: BattleRng,
      rules: BattleRules = BattleRules(),
      own: BattleSideState? = null,
      foe: BattleSideState? = null,
      field: BattleField? = null,
  ): BattleInstance {
    val battle =
        BattleInstance(
            ids.getAndIncrement(),
            charId,
            session,
            party,
            opponent,
            rng,
            rules.catchable,
            rules.escapable,
            rules.trainer,
            format = rules.format,
            terrain = rules.terrain,
            musicKey = rules.musicKey,
            underwater = rules.underwater,
            own = own ?: BattleSideState(MutableList(rules.format) { it }),
            foe = foe ?: BattleSideState(MutableList(rules.format) { it }),
            field = field ?: BattleField(),
        )
    byChar[charId] = battle
    return battle
  }

  fun byChar(charId: Long): BattleInstance? = byChar[charId]

  /** r5-frontier: a second player in the same battle (Battle Tower multi battle partner). */
  fun link(charId: Long, battle: BattleInstance) {
    byChar[charId] = battle
  }

  fun remove(charId: Long): BattleInstance? = byChar.remove(charId)
}
