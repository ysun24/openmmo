package de.fiereu.openmmo.net.game

import de.fiereu.network.Direction
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * The round-3 branches each added OpenMMO-only packets. Registering two packets on one opcode and
 * direction already throws when [GameProtocol] loads; this pins the OpenMMO-only table so a later
 * branch sees exactly which opcodes are taken (scripts/check-opcodes.py prints the same table).
 */
class OpcodeClashTest :
    FunSpec({
      val regs = GameProtocol.registrations

      test("no opcode is registered twice in a direction, no packet type twice") {
        regs.groupBy { it.opcode to it.direction }.filterValues { it.size > 1 }.keys.shouldBeEmpty()
        regs.groupBy { it.type to it.direction }.filterValues { it.size > 1 }.keys.shouldBeEmpty()
      }

      test("the OpenMMO-only round-3 opcodes are where PROTOCOL.md says") {
        fun at(op: Int, dir: Direction): String? =
            regs.firstOrNull { it.opcode.toInt() == op && it.direction == dir }?.type?.simpleName
        val c2s =
            mapOf(
                0xF0 to "BattleExtActionPacket",
                0xF2 to "EvolutionReplyPacket",
                0xF4 to "FieldActionPacket",
                0xF8 to "ProgressActionPacket",
                0xFC to "SocialRequestPacket",
                0xFD to "MarketQueryPacket",
                0xFE to "TradeOfferPcMonPacket",
            )
        val s2c =
            mapOf(
                0xE6 to "BattleExtPacket",
                0xE7 to "ProgressStatePacket",
                0xE8 to "FieldAvatarPacket",
                0xE9 to "FieldInfoPacket",
                0xFD to "CommunityPacket",
            )
        c2s.mapValues { (op, _) -> at(op, Direction.C2S) } shouldBe c2s
        s2c.mapValues { (op, _) -> at(op, Direction.S2C) } shouldBe s2c
      }
    })
