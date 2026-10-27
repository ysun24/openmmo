package de.fiereu.openmmo.server.game.script.vm

import de.fiereu.network.PacketEvent
import de.fiereu.network.SessionAttribute
import de.fiereu.network.SessionContext
import de.fiereu.openmmo.net.game.packets.script.ScriptUiPacket
import de.fiereu.openmmo.net.game.packets.script.ScriptUiReplyPacket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject

/**
 * The script interpreter's own presentation channel (s2c / c2s 0xCE): menus that wait for an
 * answer, and fire-and-forget sounds, fades, pictures and screen effects. See PROTOCOL.md
 * "r5-scripts".
 */
@Singleton
class ScriptUiService @Inject constructor() {
  private val seq = AtomicInteger()

  private class Pending(val seq: Int, val reply: CompletableDeferred<Int>)

  /** Show-and-forget. */
  fun send(session: SessionContext, kind: String, body: JsonObjectBuilder.() -> Unit = {}) {
    session.send(ScriptUiPacket(seq.incrementAndGet(), kind, buildJsonObject(body).toString()))
  }

  fun send(session: SessionContext, kind: String, json: JsonObject) {
    session.send(ScriptUiPacket(seq.incrementAndGet(), kind, json.toString()))
  }

  /**
   * Shows a menu and waits for the answer: the chosen value, or [cancelled] when the player
   * backed out, disconnected or did not answer within [TIMEOUT].
   */
  suspend fun ask(
      session: SessionContext,
      kind: String,
      cancelled: Int,
      body: JsonObjectBuilder.() -> Unit,
  ): Int {
    val s = seq.incrementAndGet()
    val p = Pending(s, CompletableDeferred())
    pending(session)[s] = p
    try {
      session.send(ScriptUiPacket(s, kind, buildJsonObject(body).toString()))
      return withTimeoutOrNull(TIMEOUT) { p.reply.await() } ?: cancelled
    } finally {
      pending(session).remove(s)
    }
  }

  fun onReply(event: PacketEvent<ScriptUiReplyPacket>) {
    pending(event.session).remove(event.packet.seq)?.reply?.complete(event.packet.value)
  }

  private fun pending(session: SessionContext): ConcurrentHashMap<Int, Pending> =
      session.attributes.getOrPut(PENDING) { ConcurrentHashMap() }

  private companion object {
    val PENDING = SessionAttribute.of<ConcurrentHashMap<Int, Pending>>("scriptUiPending")
    val TIMEOUT = 5.minutes
  }
}
