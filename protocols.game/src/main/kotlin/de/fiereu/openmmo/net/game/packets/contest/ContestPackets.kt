package de.fiereu.openmmo.net.game.packets.contest

import de.fiereu.bytecodec.CodecScope
import de.fiereu.bytecodec.PacketCodec
import de.fiereu.openmmo.net.game.packets.battle.Utf8Long

/*
 * r5-contests: the OpenMMO-only channel of Hoenn contests, the Berry Blender, the Pokéblock case,
 * berry trees and the Lilycove museum paintings. Both ends belong to this project, nothing here is
 * reverse engineered. The reserved r5-contests range 0xD4-0xD7 was already taken in both
 * directions by retail packets except c2s 0xD5, so: c2s 0xD5 ContestActionPacket and s2c 0xC6
 * ContestStatePacket (free in both directions in every r5 worktree when picked).
 */

/**
 * c2s 0xD5: one player action as a JSON object `{"t":"<kind>.<verb>", ...}`, for example
 * `{"t":"contest.move","slot":2}` or `{"t":"blender.press","pos":12345}` (PROTOCOL.md "r5-contests").
 */
data class ContestActionPacket(val json: String)

object ContestActionPacketCodec : PacketCodec<ContestActionPacket>() {
  override fun CodecScope<ContestActionPacket>.body(): ContestActionPacket =
      ContestActionPacket(field(Utf8Long) { it.json })
}

/**
 * s2c 0xC6: one document as JSON `{"k":"<kind>", ...}`; kinds are `contest`, `contestEntry`,
 * `blender`, `pokeblocks`, `berryTrees`, `painting`, `conditions` and `close` (PROTOCOL.md
 * "r5-contests"). The client keeps the last document per kind.
 */
data class ContestStatePacket(val json: String)

object ContestStatePacketCodec : PacketCodec<ContestStatePacket>() {
  override fun CodecScope<ContestStatePacket>.body(): ContestStatePacket =
      ContestStatePacket(field(Utf8Long) { it.json })
}
