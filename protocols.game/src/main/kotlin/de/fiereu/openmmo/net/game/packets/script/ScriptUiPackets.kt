package de.fiereu.openmmo.net.game.packets.script

import de.fiereu.bytecodec.CodecScope
import de.fiereu.bytecodec.PacketCodec
import de.fiereu.bytecodec.S32LE
import de.fiereu.bytecodec.Utf16LeNullTerminated

/*
 * r5-scripts: the decomp script interpreter's presentation channel. Our own packets, not reverse
 * engineered. Opcode 0xCE both directions (the reserved 0xD0-0xD3 are retail opcodes, 0xCE was free
 * in both directions).
 */

/**
 * s2c: something a running decomp script shows or plays besides a text box. [kind] names it and
 * [json] carries its arguments (a JSON object, see PROTOCOL.md "r5-scripts"):
 * - menus that answer with [ScriptUiReplyPacket]: `multichoice`, `party` (ChoosePartyMon and
 *   friends), `elevator`, `trade` (in-game trade scene, answers when it finishes), `number`;
 * - fire and forget: `sound` (se/bgm/fanfare/cry), `fade`, `monpic`, `moneybox`, `coinsbox`, `fx`
 *   (camera shake, flash, weather...), `metatile` (a map tile changed), `close` (hide one).
 *
 * [seq] increases per packet; a reply names the [seq] it answers.
 */
data class ScriptUiPacket(val seq: Int, val kind: String, val json: String)

object ScriptUiPacketCodec : PacketCodec<ScriptUiPacket>() {
  override fun CodecScope<ScriptUiPacket>.body(): ScriptUiPacket {
    val seq = field(S32LE) { it.seq }
    val kind = field(Utf16LeNullTerminated) { it.kind }
    val json = field(Utf16LeNullTerminated) { it.json }
    return ScriptUiPacket(seq, kind, json)
  }
}

/**
 * c2s: the player's answer to the [ScriptUiPacket] [seq]; [value] -1 / 127 means cancelled. [text]
 * answers a `name` prompt (the naming screen).
 */
data class ScriptUiReplyPacket(val seq: Int, val value: Int, val text: String = "")

object ScriptUiReplyPacketCodec : PacketCodec<ScriptUiReplyPacket>() {
  override fun CodecScope<ScriptUiReplyPacket>.body(): ScriptUiReplyPacket {
    val seq = field(S32LE) { it.seq }
    val value = field(S32LE) { it.value }
    val text = field(Utf16LeNullTerminated) { it.text }
    return ScriptUiReplyPacket(seq, value, text)
  }
}
