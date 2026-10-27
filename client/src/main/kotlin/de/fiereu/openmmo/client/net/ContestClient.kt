package de.fiereu.openmmo.client.net

import de.fiereu.openmmo.net.game.packets.contest.ContestActionPacket
import de.fiereu.openmmo.net.game.packets.contest.ContestStatePacket

/**
 * r5-contests: the contest / blender / Pokéblock / berry tree channel (s2c 0xC6, c2s 0xD5). Keeps
 * the last JSON document per kind (`contest`, `blender`, `pokeblocks`, `berryTrees`, `painting`,
 * `contestEntry`, `berryChoose`, `conditions`); a `close` document removes its kind. [seq] grows
 * per document so a consumer sees every change.
 */
class ContestClient(private val send: (Any) -> Unit, private val onChange: () -> Unit) {
  data class State(val docs: Map<String, String> = emptyMap(), val seq: Int = 0)

  @Volatile
  var state = State()
    private set

  private val lock = Any()

  fun onPacket(p: ContestStatePacket) {
    val kind = Regex("\"k\"\\s*:\\s*\"(\\w+)\"").find(p.json)?.groupValues?.get(1) ?: return
    synchronized(lock) {
      state =
          if (kind == "close") {
            val what = Regex("\"what\"\\s*:\\s*\"(\\w+)\"").find(p.json)?.groupValues?.get(1)
            State(state.docs - (what ?: ""), state.seq + 1)
          } else State(state.docs + (kind to p.json), state.seq + 1)
    }
    onChange()
  }

  /** Sends one action, a JSON object with its `t`. */
  fun action(json: String) = send(ContestActionPacket(json))

  fun reset() =
      synchronized(lock) { state = State(seq = state.seq + 1) }
}
