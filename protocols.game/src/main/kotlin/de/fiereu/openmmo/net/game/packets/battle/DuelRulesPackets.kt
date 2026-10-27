package de.fiereu.openmmo.net.game.packets.battle

import de.fiereu.bytecodec.CodecScope
import de.fiereu.bytecodec.PacketCodec
import de.fiereu.bytecodec.U8
import de.fiereu.bytecodec.Utf16LeNullTerminated

/*
 * Duel rules and the duel turn timer. These packets are our own, not reverse engineered: both ends
 * belong to this project. The retail DuelChallengePacket (c2s 0x5A) still works and means default
 * rules. Opcodes were free in both directions.
 */

/**
 * The rules a duel is fought under. [levelCap] 0 = no cap (monsters above it are scaled down for
 * the duel only), [maxParty] how many party monsters each side brings (1-6), [turnSeconds] 0 = no
 * turn timer.
 */
data class DuelRules(val levelCap: Int = 0, val maxParty: Int = 6, val turnSeconds: Int = 0) {
  companion object {
    val DEFAULT = DuelRules()
    const val MAX_TIMEOUTS = 3
  }
}

/** c2s: challenge the named online player under [rules]. */
data class DuelRulesChallengePacket(val targetName: String, val rules: DuelRules)

object DuelRulesChallengePacketCodec : PacketCodec<DuelRulesChallengePacket>() {
  override fun CodecScope<DuelRulesChallengePacket>.body(): DuelRulesChallengePacket {
    val name = field(Utf16LeNullTerminated) { it.targetName }
    val cap = field(U8) { it.rules.levelCap }
    val party = field(U8) { it.rules.maxParty }
    val seconds = field(U8) { it.rules.turnSeconds }
    return DuelRulesChallengePacket(name, DuelRules(cap, party, seconds))
  }
}

/**
 * s2c: the rules of a duel. Sent with an invite ([challenger] = who asked) and again to both
 * players when the duel starts ([challenger] = the opponent's name).
 */
data class DuelRulesPacket(val challenger: String, val rules: DuelRules)

object DuelRulesPacketCodec : PacketCodec<DuelRulesPacket>() {
  override fun CodecScope<DuelRulesPacket>.body(): DuelRulesPacket {
    val name = field(Utf16LeNullTerminated) { it.challenger }
    val cap = field(U8) { it.rules.levelCap }
    val party = field(U8) { it.rules.maxParty }
    val seconds = field(U8) { it.rules.turnSeconds }
    return DuelRulesPacket(name, DuelRules(cap, party, seconds))
  }
}

/**
 * s2c: a duel turn timer started: whoever owes an action has [seconds] to send it. [mustAct] tells
 * the receiver whether that includes them (false = only the rival is choosing). [timeouts] /
 * [rivalTimeouts] count the timeouts so far; at [DuelRules.MAX_TIMEOUTS] a player forfeits.
 * [autoPicked] is true when the server just acted for the receiver because their time ran out.
 */
data class BattleTurnTimerPacket(
    val seconds: Int,
    val timeouts: Int,
    val rivalTimeouts: Int,
    val mustAct: Boolean = true,
    val autoPicked: Boolean = false,
)

object BattleTurnTimerPacketCodec : PacketCodec<BattleTurnTimerPacket>() {
  override fun CodecScope<BattleTurnTimerPacket>.body(): BattleTurnTimerPacket {
    val seconds = field(U8) { it.seconds }
    val timeouts = field(U8) { it.timeouts }
    val rival = field(U8) { it.rivalTimeouts }
    val flags = field(U8) { (if (it.mustAct) 1 else 0) or (if (it.autoPicked) 2 else 0) }
    return BattleTurnTimerPacket(seconds, timeouts, rival, flags and 1 != 0, flags and 2 != 0)
  }
}
