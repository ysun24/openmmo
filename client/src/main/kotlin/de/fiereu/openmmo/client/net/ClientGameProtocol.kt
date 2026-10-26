package de.fiereu.openmmo.client.net

import de.fiereu.bytecodec.Codec
import de.fiereu.bytecodec.ReadBuffer
import de.fiereu.bytecodec.WriteBuffer
import de.fiereu.network.Direction as NetDirection
import de.fiereu.network.Protocol
import de.fiereu.openmmo.net.game.GameProtocol
import de.fiereu.openmmo.net.game.packets.CharactersListPacket
import kotlin.reflect.KClass

/**
 * [GameProtocol] as seen from a client.
 *
 * The shared codecs are written from the server's side: where the retail capture and OpenMMO's own
 * writer disagree, `read` follows the capture and `write` follows the server. A client reading
 * OpenMMO's output therefore needs a reader that mirrors the server's writer. Those overrides live
 * in [ClientCodecs]; everything else is the shared registration unchanged.
 */
object ClientGameProtocol : Protocol() {
  override val compressed: Boolean = GameProtocol.compressed

  private val s2cOverrides: Map<KClass<*>, Codec<*>> =
      mapOf(CharactersListPacket::class to ClientCodecs.CharactersList)

  init {
    for (reg in GameProtocol.registrations) {
      @Suppress("UNCHECKED_CAST")
      reg as Protocol.Registration<Any>
      when (reg.direction) {
        NetDirection.C2S -> c2s(reg.opcode, reg.codec, reg.type)
        NetDirection.S2C -> {
          @Suppress("UNCHECKED_CAST")
          val codec = (s2cOverrides[reg.type] as Codec<Any>?) ?: reg.codec
          s2c(reg.opcode, codec, reg.type)
        }
      }
    }
  }
}

/** Reads that mirror what the OpenMMO server writes. Writing is delegated unchanged. */
internal object ClientCodecs {
  val CharactersList: Codec<CharactersListPacket> =
      object : Codec<CharactersListPacket> {
        override fun read(buf: ReadBuffer): CharactersListPacket = CharactersListReader.read(buf)

        override fun write(buf: WriteBuffer, value: CharactersListPacket) =
            de.fiereu.openmmo.net.game.packets.CharactersListPacketCodec.write(buf, value)
      }
}
