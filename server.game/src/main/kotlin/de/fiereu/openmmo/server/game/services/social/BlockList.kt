package de.fiereu.openmmo.server.game.services.social

import de.fiereu.network.SessionContext
import de.fiereu.openmmo.net.game.packets.social.MapRef
import de.fiereu.openmmo.server.game.session.SessionRegistry
import de.fiereu.openmmo.server.game.storage.CharacterStore
import de.fiereu.openmmo.server.game.storage.SocialRepository
import de.fiereu.openmmo.server.game.storage.StoredCharacter
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Who blocked whom, cached per character. Chat, whispers, trade requests and guild invites all ask
 * it, so it sits apart from the services that change it.
 */
@Singleton
class BlockList @Inject constructor(private val repo: SocialRepository) {
  private val cache = ConcurrentHashMap<Long, Set<Long>>()

  suspend fun blockedBy(charId: Long): Set<Long> =
      cache[charId] ?: repo.blocked(charId).map { it.id }.toSet().also { cache[charId] = it }

  /** True when [by] blocked [who]. */
  suspend fun isBlocked(by: Long, who: Long): Boolean = who in blockedBy(by)

  /** Either side blocked the other. */
  suspend fun eitherBlocked(a: Long, b: Long): Boolean = isBlocked(a, b) || isBlocked(b, a)

  fun invalidate(charId: Long) {
    cache.remove(charId)
  }
}

/** Online characters by name and where they are. */
@Singleton
class OnlineDirectory
@Inject
constructor(
    private val sessions: SessionRegistry,
    private val characterStore: CharacterStore,
) {
  fun session(charId: Long): SessionContext? =
      sessions.getByCharacterId(charId)?.takeIf { characterStore.getCharacter(charId) != null }

  fun isOnline(charId: Long): Boolean = session(charId) != null

  fun online(): List<StoredCharacter> =
      sessions.onlineCharacterIds().mapNotNull { characterStore.getCharacter(it) }

  fun byName(name: String): StoredCharacter? {
    val wanted = name.trim()
    return online().firstOrNull { it.info.name.equals(wanted, ignoreCase = true) }
  }

  fun location(charId: Long): MapRef {
    val info = characterStore.getCharacter(charId)?.info ?: return MapRef.UNKNOWN
    if (sessions.getByCharacterId(charId) == null) return MapRef.UNKNOWN
    return MapRef(
        info.positionRegionId.toInt(), info.positionBankId.toInt(), info.positionMapId.toInt())
  }

  fun nameOf(charId: Long): String? = characterStore.getCharacter(charId)?.info?.name
}
