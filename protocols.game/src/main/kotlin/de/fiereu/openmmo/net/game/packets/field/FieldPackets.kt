package de.fiereu.openmmo.net.game.packets.field

import de.fiereu.bytecodec.CodecScope
import de.fiereu.bytecodec.PacketCodec
import de.fiereu.bytecodec.S16LE
import de.fiereu.bytecodec.S64LE
import de.fiereu.bytecodec.U16LE
import de.fiereu.bytecodec.U8
import de.fiereu.bytecodec.bytesPrefixed

/*
 * Overworld field moves and travel modes (round 3, branch r3-field). These packets are our own,
 * not reverse engineered: both ends belong to this project. c2s 0xF4 and s2c 0xE8/0xE9 were free in
 * both directions.
 */

/** What a [FieldActionPacket] asks for. Wire value = ordinal, append only. */
enum class FieldAction {
  CUT,
  FLASH,
  ROCK_SMASH,
  STRENGTH,
  SURF,
  FLY,
  DIVE,
  WATERFALL,
  TELEPORT,
  DIG,
  SWEET_SCENT,
  ESCAPE_ROPE,
  /** Get on (or off) the bicycle; [FieldActionPacket.arg] 0 = Kanto bicycle / Mach, 1 = Acro. */
  BIKE,
  /** Put the bike away. */
  DISMOUNT,
  /** Use the Itemfinder (Dowsing Machine) here. */
  ITEMFINDER,
}

/**
 * c2s 0xF4: use a field move or a field item. [arg] carries the bike type; FLY carries the target
 * as [arg] = bank, [arg2] = map (in the player's region).
 */
data class FieldActionPacket(val action: FieldAction, val arg: Int = 0, val arg2: Int = 0)

object FieldActionPacketCodec : PacketCodec<FieldActionPacket>() {
  override fun CodecScope<FieldActionPacket>.body(): FieldActionPacket {
    val action = field(U8) { it.action.ordinal }
    val arg = field(S16LE) { it.arg.toShort() }
    val arg2 = field(S16LE) { it.arg2.toShort() }
    return FieldActionPacket(
        FieldAction.entries.getOrElse(action) { FieldAction.CUT }, arg.toInt(), arg2.toInt())
  }
}

/** How an avatar travels. Wire value = ordinal, append only. */
enum class TravelMode {
  WALK,
  /** Kanto's Bicycle and Hoenn's Mach Bike. */
  MACH_BIKE,
  ACRO_BIKE,
  SURF,
  UNDERWATER,
}

/**
 * s2c 0xE8: an avatar's travel mode changed (sent to the player and its observers, and to a new
 * observer when it first sees the player). [running] is the last step's run state. [exclaim] with
 * an npc's id is a trainer's "!" when it spots the player (the decomp's
 * FLDEFF_EXCLAMATION_MARK_ICON).
 */
data class FieldAvatarPacket(
    val entityId: Long,
    val mode: TravelMode,
    val running: Boolean = false,
    val exclaim: Boolean = false,
    /**
     * Integration: the character's sprite is the region's female player (CharacterInfo.rivalSex).
     */
    val female: Boolean = false,
)

object FieldAvatarPacketCodec : PacketCodec<FieldAvatarPacket>() {
  override fun CodecScope<FieldAvatarPacket>.body(): FieldAvatarPacket {
    val id = field(S64LE) { it.entityId }
    val mode = field(U8) { it.mode.ordinal }
    val flags =
        field(U8) {
          (if (it.running) 1 else 0) or (if (it.exclaim) 2 else 0) or (if (it.female) 4 else 0)
        }
    return FieldAvatarPacket(
        id,
        TravelMode.entries.getOrElse(mode) { TravelMode.WALK },
        flags and 1 != 0,
        flags and 2 != 0,
        flags and 4 != 0)
  }
}

/** One FLY destination the player has visited, in the player's region. */
data class FlyDestination(val bank: Int, val map: Int)

/**
 * s2c 0xE9: the player's own field state, sent on every map entry and whenever it changes.
 * - [flags]: see the FLAG_* constants.
 * - [moves]: the [FieldAction]s the party can use right now (knows the move and has the badge).
 * - [fly]: visited FLY destinations.
 * - [uiSeq]/[uiKind]: a UI the server asks the client to open; [uiSeq] grows by one per request so
 *   repeats are seen, [uiKind] 0 none, 1 PC (Pokemon storage), 2 PC (item storage), 3 fly map.
 */
data class FieldInfoPacket(
    val flags: Int,
    val moves: List<FieldAction>,
    val fly: List<FlyDestination>,
    val uiSeq: Int = 0,
    val uiKind: Int = 0,
) {
  val dark: Boolean
    get() = flags and FLAG_DARK != 0

  val flash: Boolean
    get() = flags and FLAG_FLASH != 0

  companion object {
    /** The map needs FLASH (requires_flash). */
    const val FLAG_DARK = 1
    /** FLASH lights it this visit. */
    const val FLAG_FLASH = 2
    const val FLAG_CAN_RUN = 4
    const val FLAG_CAN_BIKE = 8
    const val FLAG_HAS_BIKE = 16
    /** STRENGTH is active (boulders can be pushed until the next map change). */
    const val FLAG_STRENGTH = 32
    const val FLAG_CAN_ESCAPE = 64
  }
}

object FieldInfoPacketCodec : PacketCodec<FieldInfoPacket>() {
  private val bytes = bytesPrefixed(U8)

  override fun CodecScope<FieldInfoPacket>.body(): FieldInfoPacket {
    val flags = field(U16LE) { it.flags }
    val moves = field(bytes) { p -> ByteArray(p.moves.size) { p.moves[it].ordinal.toByte() } }
    val fly =
        field(bytes) { p ->
          ByteArray(p.fly.size * 2) { i ->
            val d = p.fly[i / 2]
            (if (i % 2 == 0) d.bank else d.map).toByte()
          }
        }
    val seq = field(U16LE) { it.uiSeq and 0xFFFF }
    val kind = field(U8) { it.uiKind }
    return FieldInfoPacket(
        flags,
        moves.toList().mapNotNull { FieldAction.entries.getOrNull(it.toInt() and 0xFF) },
        List(fly.size / 2) {
          FlyDestination(fly[it * 2].toInt() and 0xFF, fly[it * 2 + 1].toInt() and 0xFF)
        },
        seq,
        kind,
    )
  }
}
