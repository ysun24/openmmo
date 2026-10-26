package de.fiereu.openmmo.net.game.packets.market

import de.fiereu.bytecodec.Bool
import de.fiereu.bytecodec.CodecScope
import de.fiereu.bytecodec.PacketCodec
import de.fiereu.bytecodec.S16LE
import de.fiereu.bytecodec.S32LE
import de.fiereu.bytecodec.S64LE
import de.fiereu.bytecodec.U16LE
import de.fiereu.bytecodec.U8
import de.fiereu.bytecodec.Utf16LeNullTerminated
import de.fiereu.bytecodec.listPrefixed
import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.net.game.codecs.PokemonCodec

/*
 * OpenMMO-only market packets. The retail GTL packets cover creating, searching, buying and
 * cancelling (see GameProtocol), but nothing captured reports the outcome of one of those actions
 * or the goods a player could list, so these fill the gap. We own both ends, so the layouts are
 * ours.
 */

/** Asks for a [MarketInventoryPacket]. */
class MarketInventoryRequestPacket

object MarketInventoryRequestPacketCodec : PacketCodec<MarketInventoryRequestPacket>() {
  override fun CodecScope<MarketInventoryRequestPacket>.body() = MarketInventoryRequestPacket()
}

data class MarketItemStack(val itemId: Short, val quantity: Int)

/** What the player could put on the market: money, the party, the pc size and the bag. */
data class MarketInventoryPacket(
    val money: Int,
    val party: List<Pokemon>,
    val pcCount: Short,
    val items: List<MarketItemStack>,
)

object MarketInventoryPacketCodec : PacketCodec<MarketInventoryPacket>() {
  private val ItemStackCodec =
      object : PacketCodec<MarketItemStack>() {
        override fun CodecScope<MarketItemStack>.body(): MarketItemStack {
          val itemId = field(S16LE) { it.itemId }
          val quantity = field(S32LE) { it.quantity }
          return MarketItemStack(itemId, quantity)
        }
      }

  override fun CodecScope<MarketInventoryPacket>.body(): MarketInventoryPacket {
    val money = field(S32LE) { it.money }
    val party = field(PokemonCodec.listPrefixed(U8)) { it.party }
    val pcCount = field(S16LE) { it.pcCount }
    val items = field(ItemStackCodec.listPrefixed(U16LE)) { it.items }
    return MarketInventoryPacket(money, party, pcCount, items)
  }
}

enum class MarketAction(val id: Int) {
  CREATE(0),
  BUY(1),
  CANCEL(2),
  /** Pushed to a seller whose listing was bought. */
  SOLD(3),
  /** Pushed to a seller whose listing ran out and was returned. */
  EXPIRED(4),
  SEARCH(5);

  companion object {
    fun of(id: Int): MarketAction = entries.firstOrNull { it.id == id } ?: SEARCH
  }
}

/** The outcome of a market action, or a push about one of the player's listings. */
data class MarketResultPacket(
    val action: MarketAction,
    val success: Boolean,
    val listingId: Long,
    val message: String,
)

object MarketResultPacketCodec : PacketCodec<MarketResultPacket>() {
  override fun CodecScope<MarketResultPacket>.body(): MarketResultPacket {
    val action = MarketAction.of(field(U8) { it.action.id })
    val success = field(Bool) { it.success }
    val listingId = field(S64LE) { it.listingId }
    val message = field(Utf16LeNullTerminated) { it.message }
    return MarketResultPacket(action, success, listingId, message)
  }
}
