package de.fiereu.openmmo.server.game.minigame

import de.fiereu.network.SessionContext
import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.net.game.packets.LocalCharacterDeltaPacket
import de.fiereu.openmmo.net.game.packets.PokemonContainerPacket
import de.fiereu.openmmo.net.game.packets.CurrencyBalancePacket
import de.fiereu.openmmo.server.game.battle.BattleRng
import de.fiereu.openmmo.server.game.battle.Shininess
import de.fiereu.openmmo.server.game.battle.WildMonFactory
import de.fiereu.openmmo.server.game.services.itemStackUpdatePacket
import de.fiereu.openmmo.server.game.services.notice
import de.fiereu.openmmo.server.game.session.PlayerState
import de.fiereu.openmmo.server.game.storage.CharacterStore
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/**
 * A server-driven list menu (topic "menu"), the decomp's multichoice / ScriptMenu: a script shows
 * it and waits for the index the player picks, or -1 for B (MULTI_B_PRESSED).
 */
@Singleton
class MinigameMenus @Inject constructor() : MinigameTopic {
  override val topics = setOf("menu")
  private val pending = ConcurrentHashMap<Long, CompletableDeferred<Int>>()
  private var seq = 0

  /**
   * Shows [options] titled [title] and waits for the choice. [detail] is an optional second line
   * per option (a price). -1 when cancelled, timed out or the player left.
   */
  suspend fun choose(
      session: SessionContext,
      charId: Long,
      title: String,
      options: List<String>,
      detail: List<String> = emptyList(),
      cancel: Boolean = true,
  ): Int {
    val d = CompletableDeferred<Int>()
    pending.put(charId, d)?.complete(-1)
    MinigameChannel.send(
        session,
        "menu",
        jsonOf(
            "seq" to ++seq,
            "title" to title,
            "options" to options,
            "detail" to detail,
            "cancel" to cancel))
    try {
      return withTimeoutOrNull(MENU_TIMEOUT) { d.await() } ?: -1
    } finally {
      pending.remove(charId, d)
      MinigameChannel.close(session, "menu")
    }
  }

  override suspend fun onAction(
      session: SessionContext,
      state: PlayerState,
      topic: String,
      body: JsonObject
  ) {
    val charId = state.characterId ?: return
    val i = body.int("choice") ?: -1
    pending.remove(charId)?.complete(i)
  }

  override fun onDisconnect(charId: Long) {
    pending.remove(charId)?.complete(-1)
  }

  private companion object {
    val MENU_TIMEOUT = 5.minutes
  }
}

/** Money, items and gift monsters for this lane's prizes, synced to the client. */
@Singleton
class MinigameRewards
@Inject
constructor(
    private val characters: CharacterStore,
    private val wildMons: WildMonFactory,
) {
  fun money(charId: Long): Int = characters.getCharacter(charId)?.info?.money ?: 0

  suspend fun addMoney(session: SessionContext, charId: Long, amount: Int): Boolean {
    if (!characters.addMoney(charId, amount)) return false
    val m = money(charId)
    session.send(CurrencyBalancePacket(m, 0))
    session.send(LocalCharacterDeltaPacket(money = m))
    return true
  }

  fun itemCount(charId: Long, itemId: Int): Int =
      characters.getCharacter(charId)?.items?.get(itemId) ?: 0

  suspend fun addItem(session: SessionContext, charId: Long, itemId: Int, qty: Int): Boolean {
    if (!characters.addItem(charId, itemId, qty)) return false
    session.send(itemStackUpdatePacket(itemId, itemCount(charId, itemId)))
    return true
  }

  /**
   * The decomp's givemon: a level [level] [dexId] with its level-up moves, into the party or, when
   * full, the PC. Returns where it went (PARTY / PC) or null.
   */
  suspend fun giveMon(session: SessionContext, charId: Long, dexId: Int, level: Int): PokemonContainer? {
    val stored = characters.getCharacter(charId) ?: return null
    val rolled = wildMons.create(dexId, level, BattleRng()) ?: return null
    val otId = Shininess.otId(charId)
    val base: Pokemon = rolled.copy(isShiny = Shininess.isShiny(rolled.seed, otId))
    if (stored.pokemon.size < MAX_PARTY) {
      val slot = ((stored.pokemon.maxOfOrNull { it.containerSlot } ?: -1) + 1).toShort()
      val mon =
          base.copy(
              ownerId = charId,
              container = PokemonContainer.PARTY,
              containerSlot = slot,
              ot = stored.info.name)
      if (!characters.addPokemon(charId, mon)) return null
      val party = characters.getCharacter(charId)?.pokemon ?: emptyList()
      session.send(PokemonContainerPacket(PokemonContainer.PARTY, true, false, party))
      return PokemonContainer.PARTY
    }
    var placed = false
    val ok =
        characters.commitDurably(charId) { s ->
          val used = s.pcStorage.map { it.containerSlot.toInt() }.toSet()
          val slot = (0 until MAX_PC).firstOrNull { it !in used } ?: return@commitDurably null
          placed = true
          s.copy(
              pcStorage =
                  (s.pcStorage +
                          base.copy(
                              ownerId = charId,
                              container = PokemonContainer.PC,
                              containerSlot = slot.toShort(),
                              ot = s.info.name))
                      .toMutableList())
        }
    if (!ok || !placed) return null
    val pc = characters.getCharacter(charId)?.pcStorage ?: emptyList()
    session.send(PokemonContainerPacket(PokemonContainer.PC, true, false, pc))
    return PokemonContainer.PC
  }

  fun tell(session: SessionContext, text: String) = session.send(notice(text))

  private companion object {
    const val MAX_PARTY = 6
    const val MAX_PC = 14 * 30
  }
}
