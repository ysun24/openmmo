package de.fiereu.openmmo.net.game.packets.battle

import de.fiereu.bytecodec.Codec
import de.fiereu.bytecodec.CodecScope
import de.fiereu.bytecodec.PacketCodec
import de.fiereu.bytecodec.ReadBuffer
import de.fiereu.bytecodec.S16LE
import de.fiereu.bytecodec.S64LE
import de.fiereu.bytecodec.S8
import de.fiereu.bytecodec.U8
import de.fiereu.bytecodec.WriteBuffer

/*
 * r3-battle: the OpenMMO battle extension channel. Both ends belong to this project; nothing here
 * is reverse engineered. The retail-shaped battle packets still carry moves, hp, stat stages and
 * switches; this channel adds what they cannot say: major status, confusion, abilities, held items,
 * weather, the catch shake count, double battle positions, bag item results and the evolution
 * scene. Opcodes: s2c 0xE6 BattleExtPacket, c2s 0xF0 BattleExtActionPacket, c2s 0xF2
 * EvolutionReplyPacket (all free in both directions in every r3 worktree when picked).
 */

/** A UTF-8 string with a 32-bit little-endian byte length. */
object Utf8Long : Codec<String> {
  override fun read(buf: ReadBuffer): String {
    val b0 = buf.readByte().toInt() and 0xFF
    val b1 = buf.readByte().toInt() and 0xFF
    val b2 = buf.readByte().toInt() and 0xFF
    val b3 = buf.readByte().toInt() and 0xFF
    val len = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    require(len in 0..buf.remaining()) { "bad string length $len" }
    val bytes = ByteArray(len)
    buf.readBytes(bytes)
    return String(bytes, Charsets.UTF_8)
  }

  override fun write(buf: WriteBuffer, value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    val len = bytes.size
    buf.writeByte(len.toByte())
    buf.writeByte((len shr 8).toByte())
    buf.writeByte((len shr 16).toByte())
    buf.writeByte((len shr 24).toByte())
    buf.writeBytes(bytes)
  }
}

/**
 * s2c 0xE6: a JSON document, one of
 * - `{"t":"state", ...}` the battle's extended state (format, positions, per-monster status...),
 * - `{"t":"events","e":[...]}` an ordered batch of extension events between retail packets,
 * - `{"t":"evo", ...}` the evolution scene, see PROTOCOL.md (r3-battle).
 */
data class BattleExtPacket(val json: String)

object BattleExtPacketCodec : PacketCodec<BattleExtPacket>() {
  override fun CodecScope<BattleExtPacket>.body(): BattleExtPacket =
      BattleExtPacket(field(Utf8Long) { it.json })
}

/** [BattleExtActionPacket.kind] values. */
object BattleExtKind {
  const val MOVE = 0
  const val ITEM = 1
  const val SWITCH = 2
  const val RUN = 3
}

/**
 * c2s 0xF0: one battle position's pick with an explicit target. [position] is the player's battle
 * position (0, or 1 in a double battle). For MOVE [id] is the move and [targetSide]/[targetPos] the
 * battler aimed at (side 1 = the opposing side; -1 lets the server pick). For ITEM [id] is the item,
 * [targetEntity] the party monster it is used on (0 for balls / X items) and [targetPos] the move
 * slot for one-move PP items. For SWITCH [id] is the party slot.
 */
data class BattleExtActionPacket(
    val position: Int,
    val kind: Int,
    val id: Short,
    val targetSide: Int = -1,
    val targetPos: Int = -1,
    val targetEntity: Long = 0,
)

object BattleExtActionPacketCodec : PacketCodec<BattleExtActionPacket>() {
  override fun CodecScope<BattleExtActionPacket>.body(): BattleExtActionPacket {
    val position = field(U8) { it.position }
    val kind = field(U8) { it.kind }
    val id = field(S16LE) { it.id }
    val targetSide = field(S8) { it.targetSide.toByte() }
    val targetPos = field(S8) { it.targetPos.toByte() }
    val entity = field(S64LE) { it.targetEntity }
    return BattleExtActionPacket(position, kind, id, targetSide.toInt(), targetPos.toInt(), entity)
  }
}

/** c2s 0xF2: the player's answer to an evolution scene: [proceed] false = pressed B to stop it. */
data class EvolutionReplyPacket(val entityId: Long, val proceed: Boolean)

object EvolutionReplyPacketCodec : PacketCodec<EvolutionReplyPacket>() {
  override fun CodecScope<EvolutionReplyPacket>.body(): EvolutionReplyPacket {
    val id = field(S64LE) { it.entityId }
    val proceed = field(U8) { if (it.proceed) 1 else 0 }
    return EvolutionReplyPacket(id, proceed != 0)
  }
}
