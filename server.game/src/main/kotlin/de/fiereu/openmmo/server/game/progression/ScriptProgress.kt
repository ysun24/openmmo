package de.fiereu.openmmo.server.game.progression

import de.fiereu.network.SessionContext
import de.fiereu.openmmo.server.game.services.notice
import de.fiereu.openmmo.server.game.storage.CharacterStore
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What map scripts of this branch reach through
 * [de.fiereu.openmmo.server.game.script.ScriptContext]: the day care (with a party / day care
 * picker the script waits on, like the decomp's ChooseSendDaycareMon and ShowDaycareLevelMenu),
 * decoration shops and badges.
 */
@Singleton
class ScriptProgress
@Inject
constructor(
    val characters: CharacterStore,
    val progress: PlayerProgressService,
    val daycare: DaycareService,
    val decorations: DecorationService,
) {
  private val picks = ConcurrentHashMap<Long, CompletableDeferred<Long?>>()

  /**
   * Shows the picker ([mode] 1 party, 2 day care) and waits for the player's choice: a party
   * monster id, or a day care index. Null when cancelled, timed out or disconnected.
   */
  suspend fun pick(session: SessionContext, charId: Long, mode: Int): Long? {
    val deferred = CompletableDeferred<Long?>()
    picks.put(charId, deferred)?.complete(null)
    progress.setDaycareOpen(charId, mode)
    progress.push(session, charId)
    try {
      return withTimeoutOrNull(PICK_TIMEOUT) { deferred.await() }
    } finally {
      picks.remove(charId, deferred)
      progress.setDaycareOpen(charId, 0)
      progress.push(session, charId)
    }
  }

  /** The player's answer to [pick]. False when no picker was waiting. */
  fun answer(charId: Long, value: Long?): Boolean = picks.remove(charId)?.complete(value) ?: false

  fun state(charId: Long): DaycareService.State =
      characters.getCharacter(charId)?.let(daycare::state) ?: DaycareService.State.EMPTY

  fun tell(session: SessionContext, text: String) {
    session.send(notice(text))
  }

  private companion object {
    val PICK_TIMEOUT = 3.minutes
  }
}
