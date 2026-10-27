package de.fiereu.openmmo.net.game.packets.field

import de.fiereu.bytecodec.CodecScope
import de.fiereu.bytecodec.PacketCodec
import de.fiereu.bytecodec.U16LE
import de.fiereu.bytecodec.U8
import de.fiereu.bytecodec.bytesPrefixed
import de.fiereu.bytecodec.stringPrefixed

/*
 * Emerald secret bases (round 4, branch r4-gaps). Our own packets: c2s 0xF5 and s2c 0xED were free
 * in both directions.
 */

/** One decoration placed in a base: [x] is its left column, [y] its bottom row (decoration.c). */
data class PlacedDecoration(val decorId: Int, val x: Int, val y: Int)

/**
 * s2c 0xED: the player's secret base state, sent on every map entry and whenever it changes.
 * - Outdoors, [openSpots] are the spots on this map whose entrance is open for this player (its own
 *   base, a friend's base): the client draws the open metatile there.
 * - Inside a base ([inside] 1 = the player's own, 2 = a friend's), [owner] and the [decorations].
 * - [uiSeq]/[uiKind]: the server asks for a UI, once per new seq; [uiKind] 1 = the base PC menu.
 * - [myBaseId]: the player's own base (SECRET_BASE_* id), 0 when it has none.
 */
data class SecretBaseStatePacket(
    val inside: Int = 0,
    val owner: String = "",
    val baseId: Int = 0,
    val decorations: List<PlacedDecoration> = emptyList(),
    val openSpots: List<Pair<Int, Int>> = emptyList(),
    val uiSeq: Int = 0,
    val uiKind: Int = 0,
    val myBaseId: Int = 0,
) {
  companion object {
    const val OUTSIDE = 0
    const val OWN = 1
    const val VISITING = 2
    const val UI_PC = 1
  }
}

object SecretBaseStatePacketCodec : PacketCodec<SecretBaseStatePacket>() {
  private val bytes = bytesPrefixed(U8)
  private val name = stringPrefixed(U8, Charsets.UTF_8)

  override fun CodecScope<SecretBaseStatePacket>.body(): SecretBaseStatePacket {
    val inside = field(U8) { it.inside }
    val owner = field(name) { it.owner }
    val baseId = field(U16LE) { it.baseId }
    val decor =
        field(bytes) { p ->
          ByteArray(p.decorations.size * 3) { i ->
            val d = p.decorations[i / 3]
            (when (i % 3) {
                  0 -> d.decorId
                  1 -> d.x
                  else -> d.y
                })
                .toByte()
          }
        }
    val spots =
        field(bytes) { p ->
          ByteArray(p.openSpots.size * 2) { i ->
            val s = p.openSpots[i / 2]
            (if (i % 2 == 0) s.first else s.second).toByte()
          }
        }
    val seq = field(U16LE) { it.uiSeq and 0xFFFF }
    val kind = field(U8) { it.uiKind }
    val mine = field(U16LE) { it.myBaseId }
    fun u(b: Byte) = b.toInt() and 0xFF
    return SecretBaseStatePacket(
        inside,
        owner,
        baseId,
        List(decor.size / 3) {
          PlacedDecoration(u(decor[it * 3]), u(decor[it * 3 + 1]), u(decor[it * 3 + 2]))
        },
        List(spots.size / 2) { u(spots[it * 2]) to u(spots[it * 2 + 1]) },
        seq,
        kind,
        mine,
    )
  }
}

/** What a [SecretBaseActionPacket] asks for. Wire value = ordinal, append only. */
enum class SecretBaseAction {
  /** Place [SecretBaseActionPacket.decorId] with its bottom-left at (x, y). */
  PLACE,
  /** Put the decoration covering (x, y) back in the PC. */
  PUT_AWAY,
  /** Pack up: the base goes, every decoration returns to the PC (MoveOutOfSecretBase). */
  PACK_UP,
  /** The PC menu was closed. */
  CLOSE,
}

/** c2s 0xF5: a base PC action, only valid inside the player's own base. */
data class SecretBaseActionPacket(
    val action: SecretBaseAction,
    val decorId: Int = 0,
    val x: Int = 0,
    val y: Int = 0,
)

object SecretBaseActionPacketCodec : PacketCodec<SecretBaseActionPacket>() {
  override fun CodecScope<SecretBaseActionPacket>.body(): SecretBaseActionPacket {
    val a = field(U8) { it.action.ordinal }
    val d = field(U16LE) { it.decorId }
    val x = field(U8) { it.x }
    val y = field(U8) { it.y }
    return SecretBaseActionPacket(
        SecretBaseAction.entries.getOrElse(a) { SecretBaseAction.CLOSE }, d, x, y)
  }
}

/** One registered Match Call trainer (r4). */
data class MatchCallEntry(
    val trainerId: Int,
    val name: String,
    val route: String,
    val ready: Boolean
)

/**
 * s2c 0xE0: the Pokénav's Match Call list (Emerald), sent when the player asks for it
 * (FieldAction.MATCH_CALL with arg -1). Free in both directions.
 */
data class MatchCallListPacket(val entries: List<MatchCallEntry>)

object MatchCallListPacketCodec : PacketCodec<MatchCallListPacket>() {
  private val name = stringPrefixed(U8, Charsets.UTF_8)
  private val list =
      object : de.fiereu.bytecodec.Codec<List<MatchCallEntry>> {
        override fun read(buf: de.fiereu.bytecodec.ReadBuffer): List<MatchCallEntry> {
          val n = U8.read(buf)
          return List(n) {
            MatchCallEntry(U16LE.read(buf), name.read(buf), name.read(buf), U8.read(buf) != 0)
          }
        }

        override fun write(buf: de.fiereu.bytecodec.WriteBuffer, value: List<MatchCallEntry>) {
          U8.write(buf, value.size)
          for (e in value) {
            U16LE.write(buf, e.trainerId)
            name.write(buf, e.name)
            name.write(buf, e.route)
            U8.write(buf, if (e.ready) 1 else 0)
          }
        }
      }

  override fun CodecScope<MatchCallListPacket>.body(): MatchCallListPacket =
      MatchCallListPacket(field(list) { it.entries.take(255) })
}
