package de.fiereu.network

import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import java.net.SocketAddress
import java.time.Instant

interface SessionContext {
  val side: Side

  val channel: Channel

  val remoteAddress: SocketAddress

  val phase: SessionPhase

  val handshakeCompletedAt: Instant?

  val attributes: SessionAttributes

  fun send(packet: Any): ChannelFuture

  /**
   * Like [send], but never overtakes a packet another thread already handed to this channel: a
   * write from the channel's own event loop goes out immediately and would pass writes other
   * threads queued on that loop moments earlier. Use it for state that several threads push to the
   * same player (a trade window both partners edit), where the last packet must be the newest.
   */
  fun sendOrdered(packet: Any) {
    send(packet)
  }

  fun close(reason: () -> String = { "no reason" })

  fun onPhase(phase: SessionPhase, listener: () -> Unit)
}
