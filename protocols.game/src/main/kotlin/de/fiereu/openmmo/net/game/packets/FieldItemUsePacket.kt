package de.fiereu.openmmo.net.game.packets

import de.fiereu.bytecodec.CodecScope
import de.fiereu.bytecodec.PacketCodec
import de.fiereu.bytecodec.S16LE
import de.fiereu.bytecodec.S64LE

/**
 * Use a bag item in the field, outside a battle: a Potion on a party monster, a Repel on the
 * player. [pokemonId] is the target monster's id, 0 for items without a target. Our own packet, not
 * one from a capture.
 */
data class FieldItemUsePacket(
    val itemId: Short,
    val pokemonId: Long,
)

object FieldItemUsePacketCodec : PacketCodec<FieldItemUsePacket>() {
  override fun CodecScope<FieldItemUsePacket>.body(): FieldItemUsePacket {
    val itemId = field(S16LE) { it.itemId }
    val pokemonId = field(S64LE) { it.pokemonId }
    return FieldItemUsePacket(itemId, pokemonId)
  }
}
