package de.fiereu.openmmo.net.game.packets.frontier

import de.fiereu.bytecodec.CodecScope
import de.fiereu.bytecodec.PacketCodec
import de.fiereu.openmmo.net.game.packets.battle.Utf8Long

/*
 * r5-frontier: the OpenMMO battle facility channel (Battle Frontier, Battle Tents, Trainer Hill,
 * FRLG Trainer Tower, the Exchange Service Corner). Both ends belong to this project. c2s 0xD8
 * FrontierActionPacket (in the r5-frontier range 0xD8-0xDB) and s2c 0xE2 FrontierStatePacket
 * (s2c 0xD8-0xDB are retail packets, 0xE2 was free in every r5 worktree). See PROTOCOL.md
 * "r5-frontier".
 */

/** c2s 0xD8: one facility action as JSON, `{"a":"<action>", ...}`. */
data class FrontierActionPacket(val json: String)

object FrontierActionPacketCodec : PacketCodec<FrontierActionPacket>() {
  override fun CodecScope<FrontierActionPacket>.body(): FrontierActionPacket =
      FrontierActionPacket(field(Utf8Long) { it.json })
}

/** s2c 0xE2: the facility screen the player is on, as JSON (`{"open":false}` when none). */
data class FrontierStatePacket(val json: String)

object FrontierStatePacketCodec : PacketCodec<FrontierStatePacket>() {
  override fun CodecScope<FrontierStatePacket>.body(): FrontierStatePacket =
      FrontierStatePacket(field(Utf8Long) { it.json })
}
