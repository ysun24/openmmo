package de.fiereu.openmmo.client.net

import de.fiereu.openmmo.net.game.packets.minigame.MINIGAME_BODY_MAX
import de.fiereu.openmmo.net.game.packets.minigame.MinigameActionPacket
import de.fiereu.openmmo.net.game.packets.minigame.MinigameStatePacket
import java.util.concurrent.ConcurrentHashMap

/**
 * r5-minigames: the Game Corner, link minigames, Union Room, Mystery Gift, Fame Checker, records
 * and Easy Chat. The server pushes one JSON object per topic ([MinigameStatePacket], s2c 0xDD); the
 * client keeps the last one of each and sends actions as [MinigameActionPacket] (c2s 0xDC).
 */
class MinigameClient(
    private val send: (Any) -> Unit,
    private val onChange: () -> Unit,
) {
  private val topics = ConcurrentHashMap<String, String>()

  /** Topic -> its last JSON body. A topic whose body is `{}` is closed and left out. */
  fun snapshot(): Map<String, String> = topics.toSortedMap()

  fun onState(p: MinigameStatePacket) {
    if (p.body.isBlank() || p.body.trim() == "{}") topics.remove(p.topic)
    else topics[p.topic] = p.body
    onChange()
  }

  /** Sends [body] (a JSON object's text) to [topic]. False when it is too long to send. */
  fun act(topic: String, body: String): Boolean {
    if (topic.isEmpty() || topic.length > 32) return false
    if (body.toByteArray(Charsets.UTF_8).size > MINIGAME_BODY_MAX) return false
    send(MinigameActionPacket(topic, body))
    return true
  }
}
