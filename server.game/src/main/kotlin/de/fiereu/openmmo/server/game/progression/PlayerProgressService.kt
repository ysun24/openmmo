package de.fiereu.openmmo.server.game.progression

import de.fiereu.network.SessionContext
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.net.game.packets.PokemonContainerPacket
import de.fiereu.openmmo.net.game.packets.progress.DaycareView
import de.fiereu.openmmo.net.game.packets.progress.DecorStack
import de.fiereu.openmmo.net.game.packets.progress.LearnPrompt
import de.fiereu.openmmo.net.game.packets.progress.MonExtra
import de.fiereu.openmmo.net.game.packets.progress.ProgressStatePacket
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import de.fiereu.openmmo.server.game.session.PLAYER_STATE
import de.fiereu.openmmo.server.game.session.SessionRegistry
import de.fiereu.openmmo.server.game.storage.CharacterStore
import de.fiereu.openmmo.server.game.storage.StoredCharacter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject
import javax.inject.Singleton

/** Story var keys this branch owns. The `openmmo/` prefix keeps them off the GBA var table. */
object ProgressKeys {
  const val PLAY_SECONDS = "openmmo/PLAY_SECONDS"
  const val REGISTERED_ITEM = "openmmo/REGISTERED_ITEM"
  const val DAYCARE_STEPS = "openmmo/DAYCARE_STEPS"
  const val DAYCARE_EGG = "openmmo/DAYCARE_EGG"
  const val DECOR_PREFIX = "openmmo/DECOR/"
  /** Exp a day care monster had when it was left, by its slot (0/1), to bill and report growth. */
  const val DAYCARE_XP_PREFIX = "openmmo/DAYCARE_XP/"
  const val EGG_STEPS_PREFIX = "openmmo/EGG_STEPS/"

  fun decor(id: Int) = "$DECOR_PREFIX$id"
}

/**
 * Builds and pushes the [ProgressStatePacket] (pokedex, badges, trainer card numbers, monster
 * status/PP bonuses, day care, decorations, a pending move to learn) and keeps the transient UI
 * state behind it: the learn queue, an open day care picker, an open decoration shop.
 */
@Singleton
class PlayerProgressService
@Inject
constructor(
    private val characters: CharacterStore,
    private val sessions: SessionRegistry,
    private val species: SpeciesRegistry,
) {
  private val learnQueue = ConcurrentHashMap<Long, ConcurrentLinkedDeque<LearnPrompt>>()
  private val daycareOpen = ConcurrentHashMap<Long, Int>()
  private val decorShops = ConcurrentHashMap<Long, List<Int>>()
  /** When the current play-time slice started, per online character. */
  private val sessionStart = ConcurrentHashMap<Long, Long>()

  fun queueLearn(charId: Long, prompts: Collection<LearnPrompt>) {
    if (prompts.isEmpty()) return
    learnQueue.computeIfAbsent(charId) { ConcurrentLinkedDeque() }.addAll(prompts)
  }

  fun currentLearn(charId: Long): LearnPrompt? = learnQueue[charId]?.peekFirst()

  fun popLearn(charId: Long): LearnPrompt? = learnQueue[charId]?.pollFirst()

  fun setDaycareOpen(charId: Long, mode: Int) {
    if (mode == 0) daycareOpen.remove(charId) else daycareOpen[charId] = mode
  }

  fun daycareMode(charId: Long): Int = daycareOpen[charId] ?: 0

  fun openDecorShop(charId: Long, stock: List<Int>) {
    decorShops[charId] = stock
  }

  fun decorShop(charId: Long): List<Int>? = decorShops[charId]

  fun closeDecorShop(charId: Long): Boolean = decorShops.remove(charId) != null

  /** Starts counting play time; called once the character is in the world. */
  fun onJoin(charId: Long) {
    sessionStart[charId] = System.currentTimeMillis()
  }

  /** Moves the whole seconds played since the last bank into the persisted var. */
  fun bankPlayTime(charId: Long) {
    val now = System.currentTimeMillis()
    var seconds = 0
    sessionStart.computeIfPresent(charId) { _, start ->
      seconds = ((now - start) / 1000).toInt()
      start + seconds * 1000L
    }
    if (seconds <= 0) return
    val total = characters.getCharacter(charId)?.storyVars?.get(ProgressKeys.PLAY_SECONDS) ?: 0
    characters.setStoryVar(charId, ProgressKeys.PLAY_SECONDS, total + seconds)
  }

  fun playSeconds(charId: Long): Int {
    val stored = characters.getCharacter(charId)?.storyVars?.get(ProgressKeys.PLAY_SECONDS) ?: 0
    val running =
        sessionStart[charId]?.let { ((System.currentTimeMillis() - it) / 1000).toInt() } ?: 0
    return stored + running
  }

  /** Banks the last slice and forgets the character's transient state. */
  fun onLeave(charId: Long) {
    bankPlayTime(charId)
    sessionStart.remove(charId)
    learnQueue.remove(charId)
    daycareOpen.remove(charId)
    decorShops.remove(charId)
  }

  fun build(charId: Long, stored: StoredCharacter, regionId: Int): ProgressStatePacket {
    val owned = stored.pokemon + stored.pcStorage + stored.daycare
    val daycareView = daycareView(stored, daycareMode(charId))
    return ProgressStatePacket(
        seen = ProgressStatePacket.bits(stored.dexSeen + stored.dexCaught),
        caught = ProgressStatePacket.bits(stored.dexCaught),
        badges = BadgeService.mask(stored),
        playSeconds = playSeconds(charId),
        registeredItem = (stored.storyVars[ProgressKeys.REGISTERED_ITEM] ?: 0).toShort(),
        extras =
            owned
                .filter { it.status != 0 || it.ppBonuses != 0 }
                .map { MonExtra(it.id, it.status.toShort(), it.ppBonuses) },
        daycare = daycareView,
        decorations =
            stored.storyVars
                .filterKeys { it.startsWith(ProgressKeys.DECOR_PREFIX) }
                .mapNotNull { (k, v) ->
                  k.removePrefix(ProgressKeys.DECOR_PREFIX).toIntOrNull()?.let {
                    DecorStack(it.toShort(), v.toShort())
                  }
                }
                .sortedBy { it.decorId },
        decorShop = decorShop(charId).orEmpty().map { it.toShort() },
        learn = currentLearn(charId),
        permissions = stored.info.permissions,
        region = regionId,
    )
  }

  /** What the day care shows: its monsters with fee and growth, and a waiting egg. */
  fun daycareView(stored: StoredCharacter, mode: Int): DaycareView {
    // The open picker lists the day care it belongs to (3 = Route 5); otherwise all of them.
    val mons =
        when (mode) {
          2 -> stored.daycare.filter { it.containerSlot < DaycareRules.SLOTS }
          3 -> stored.daycare.filter { it.containerSlot.toInt() == DaycareService.ROUTE5_SLOT }
          else -> stored.daycare
        }.sortedBy { it.containerSlot }
    val gained =
        mons.map { DaycareRules.levelsGained(stored, it, species.get(it.dexId)?.growthRate) }
    return DaycareView(
        open = mode,
        mons = mons,
        fees = gained.map { DaycareRules.fee(it) },
        levelsGained = gained,
        eggWaiting = (stored.storyVars[ProgressKeys.DAYCARE_EGG] ?: 0) != 0,
    )
  }

  /** Pushes the state to the character's session, if online. */
  fun push(charId: Long) {
    val session = sessions.getByCharacterId(charId) ?: return
    push(session, charId)
  }

  fun push(session: SessionContext, charId: Long) {
    val stored = characters.getCharacter(charId) ?: return
    val region = session.attributes[PLAYER_STATE]?.regionId ?: 0
    session.send(build(charId, stored, region))
  }

  /** The party as it is now, for after a change to it. */
  fun pushParty(session: SessionContext, charId: Long) {
    val after = characters.getCharacter(charId) ?: return
    session.send(
        PokemonContainerPacket(
            container = PokemonContainer.PARTY,
            hasChange = true,
            delete = false,
            pokemon = after.pokemon.sortedBy { it.containerSlot },
        ))
  }

  fun pushPc(session: SessionContext, charId: Long) {
    val after = characters.getCharacter(charId) ?: return
    session.send(
        PokemonContainerPacket(
            container = PokemonContainer.PC,
            hasChange = true,
            delete = false,
            pokemon = after.pcStorage.sortedBy { it.containerSlot },
        ))
  }
}
