package de.fiereu.openmmo.net.game.packets.minigame

import de.fiereu.bytecodec.CodecScope
import de.fiereu.bytecodec.PacketCodec
import de.fiereu.bytecodec.U16LE
import de.fiereu.bytecodec.U8
import de.fiereu.bytecodec.stringPrefixed

/*
 * Round 5, branch r5-minigames: the Game Corner, the link minigames, the Union Room, Mystery Gift,
 * the Fame Checker, record mixing and Easy Chat. OpenMMO-only, both directions use the reserved
 * range: c2s 0xDC and s2c 0xDD (s2c 0xDC is the retail CategoryFlagsPacket).
 *
 * Both packets carry a [topic] ("slots", "roulette", "lobby", "berrycrush", ...) and a JSON object
 * as UTF-8 text. Every topic's fields are listed in docs/web/PROTOCOL.md, section r5-minigames.
 * JSON keeps a dozen small game UIs from needing a dozen hand-written codecs; the server parses
 * and validates every field it reads.
 */

/** Longest JSON body either side may send (the frame limit is 0xFFFF). */
const val MINIGAME_BODY_MAX = 60_000

/** c2s 0xDC: the player acts in a minigame UI. [body] is a JSON object. */
data class MinigameActionPacket(val topic: String, val body: String = "{}")

object MinigameActionPacketCodec : PacketCodec<MinigameActionPacket>() {
  private val topic = stringPrefixed(U8, Charsets.UTF_8)
  private val body = stringPrefixed(U16LE, Charsets.UTF_8)

  override fun CodecScope<MinigameActionPacket>.body(): MinigameActionPacket {
    val t = field(topic) { it.topic }
    val b = field(body) { it.body }
    return MinigameActionPacket(t, b)
  }
}

/**
 * s2c 0xDD: the state of one minigame topic for this player, sent whenever it changes. An empty
 * object (`{}`) closes the topic's UI.
 */
data class MinigameStatePacket(val topic: String, val body: String = "{}")

object MinigameStatePacketCodec : PacketCodec<MinigameStatePacket>() {
  private val topic = stringPrefixed(U8, Charsets.UTF_8)
  private val body = stringPrefixed(U16LE, Charsets.UTF_8)

  override fun CodecScope<MinigameStatePacket>.body(): MinigameStatePacket {
    val t = field(topic) { it.topic }
    val b = field(body) { it.body }
    return MinigameStatePacket(t, b)
  }
}
