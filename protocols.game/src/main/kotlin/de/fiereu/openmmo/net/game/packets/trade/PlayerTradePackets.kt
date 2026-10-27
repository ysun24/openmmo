package de.fiereu.openmmo.net.game.packets.trade

import de.fiereu.bytecodec.Bool
import de.fiereu.bytecodec.Codec
import de.fiereu.bytecodec.CodecScope
import de.fiereu.bytecodec.PacketCodec
import de.fiereu.bytecodec.ReadBuffer
import de.fiereu.bytecodec.S16LE
import de.fiereu.bytecodec.S32LE
import de.fiereu.bytecodec.S8
import de.fiereu.bytecodec.U8
import de.fiereu.bytecodec.Utf16LeNullTerminated
import de.fiereu.bytecodec.WriteBuffer
import de.fiereu.bytecodec.listPrefixed

/*
 * Player to player trading. These packets are our own, not reverse engineered: both ends belong to
 * this project. The retail opcodes that look trade related (TradeActionPacket, TradeSelectMonPacket)
 * are reused for the action and the monster pick; the request, the item offer and the window state
 * have no known retail counterpart and get fresh opcodes.
 */

/** c2s: ask the named online player to trade. */
data class TradeRequestPacket(val targetName: String)

object TradeRequestPacketCodec : PacketCodec<TradeRequestPacket>() {
  override fun CodecScope<TradeRequestPacket>.body(): TradeRequestPacket =
      TradeRequestPacket(field(Utf16LeNullTerminated) { it.targetName })
}

/** What [TradeOfferItemPacket] puts on the table. */
object TradeOfferKind {
  const val ITEM: Byte = 0
  const val MONEY: Byte = 1
}

/**
 * c2s: set how many of one bag item (or how much money, [TradeOfferKind.MONEY], [id] ignored) the
 * sender offers. An amount of 0 takes it off the table again.
 */
data class TradeOfferItemPacket(val kind: Byte, val id: Int, val amount: Int)

object TradeOfferItemPacketCodec : PacketCodec<TradeOfferItemPacket>() {
  override fun CodecScope<TradeOfferItemPacket>.body(): TradeOfferItemPacket {
    val kind = field(S8) { it.kind }
    val id = field(S32LE) { it.id }
    val amount = field(S32LE) { it.amount }
    return TradeOfferItemPacket(kind, id, amount)
  }
}

/** The action byte of the reused retail TradeActionPacket (c2s 0x50). */
object TradeAction {
  const val ACCEPT: Byte = 1
  const val DECLINE: Byte = 2
  const val READY: Byte = 3
  const val CONFIRM: Byte = 4
  const val CANCEL: Byte = 5
}

/** One monster on the table, as much as the other side needs to judge it. */
data class TradeMonView(
    val partySlot: Int,
    val dexId: Int,
    val level: Int,
    val nickname: String,
    val shiny: Boolean,
    val hp: Int,
)

data class TradeItemView(val itemId: Int, val quantity: Int)

data class TradeOfferView(
    val mons: List<TradeMonView> = emptyList(),
    val items: List<TradeItemView> = emptyList(),
    val money: Int = 0,
    /** Monsters offered from the PC (r3-social); [TradeMonView.partySlot] is the PC slot there. */
    val pcMons: List<TradeMonView> = emptyList(),
)

/** Where the window is, from the receiver's point of view. */
enum class TradePhase {
  /** No trade. Sent once when one ends, together with the reason in [TradeStatePacket.message]. */
  CLOSED,
  REQUEST_SENT,
  REQUEST_RECEIVED,
  OPEN,
  /** The swap went through. The new party follows as a regular container resync. */
  COMPLETED,
}

/** How far one side is: nothing, locked in its offer, or gave the final yes. */
enum class TradeStage {
  NONE,
  READY,
  CONFIRMED,
}

/** s2c: the whole trade window, sent after every change. */
data class TradeStatePacket(
    val phase: TradePhase,
    val partnerName: String = "",
    val mine: TradeOfferView = TradeOfferView(),
    val theirs: TradeOfferView = TradeOfferView(),
    val myStage: TradeStage = TradeStage.NONE,
    val theirStage: TradeStage = TradeStage.NONE,
    val message: String = "",
    /** Seconds until an unanswered request lapses (REQUEST_* phases), else 0. */
    val expiresInSeconds: Int = 0,
)

private object TradeMonViewCodec : Codec<TradeMonView> {
  override fun read(buf: ReadBuffer): TradeMonView =
      TradeMonView(
          partySlot = U8.read(buf),
          dexId = S32LE.read(buf),
          level = U8.read(buf),
          nickname = Utf16LeNullTerminated.read(buf),
          shiny = Bool.read(buf),
          hp = S16LE.read(buf).toInt(),
      )

  override fun write(buf: WriteBuffer, value: TradeMonView) {
    U8.write(buf, value.partySlot)
    S32LE.write(buf, value.dexId)
    U8.write(buf, value.level)
    Utf16LeNullTerminated.write(buf, value.nickname)
    Bool.write(buf, value.shiny)
    S16LE.write(buf, value.hp.toShort())
  }
}

private object TradeItemViewCodec : Codec<TradeItemView> {
  override fun read(buf: ReadBuffer): TradeItemView =
      TradeItemView(S32LE.read(buf), S32LE.read(buf))

  override fun write(buf: WriteBuffer, value: TradeItemView) {
    S32LE.write(buf, value.itemId)
    S32LE.write(buf, value.quantity)
  }
}

private val MonList = TradeMonViewCodec.listPrefixed(U8)
private val ItemList = TradeItemViewCodec.listPrefixed(U8)

private object TradeOfferViewCodec : Codec<TradeOfferView> {
  override fun read(buf: ReadBuffer): TradeOfferView =
      TradeOfferView(MonList.read(buf), ItemList.read(buf), S32LE.read(buf), MonList.read(buf))

  override fun write(buf: WriteBuffer, value: TradeOfferView) {
    MonList.write(buf, value.mons)
    ItemList.write(buf, value.items)
    S32LE.write(buf, value.money)
    MonList.write(buf, value.pcMons)
  }
}

object TradeStatePacketCodec : PacketCodec<TradeStatePacket>() {
  override fun CodecScope<TradeStatePacket>.body(): TradeStatePacket {
    val phase = TradePhase.entries[field(U8) { it.phase.ordinal }]
    val partner = field(Utf16LeNullTerminated) { it.partnerName }
    val mine = field(TradeOfferViewCodec) { it.mine }
    val theirs = field(TradeOfferViewCodec) { it.theirs }
    val myStage = TradeStage.entries[field(U8) { it.myStage.ordinal }]
    val theirStage = TradeStage.entries[field(U8) { it.theirStage.ordinal }]
    val message = field(Utf16LeNullTerminated) { it.message }
    val expires = field(S32LE) { it.expiresInSeconds }
    return TradeStatePacket(phase, partner, mine, theirs, myStage, theirStage, message, expires)
  }
}
