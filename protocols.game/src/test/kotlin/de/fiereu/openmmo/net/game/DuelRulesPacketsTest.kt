package de.fiereu.openmmo.net.game

import de.fiereu.network.Side
import de.fiereu.openmmo.common.test.decodeBytes
import de.fiereu.openmmo.common.test.encodeToBytes
import de.fiereu.openmmo.net.game.packets.battle.BattleTurnTimerPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleTurnTimerPacketCodec
import de.fiereu.openmmo.net.game.packets.battle.DuelRules
import de.fiereu.openmmo.net.game.packets.battle.DuelRulesChallengePacket
import de.fiereu.openmmo.net.game.packets.battle.DuelRulesChallengePacketCodec
import de.fiereu.openmmo.net.game.packets.battle.DuelRulesPacket
import de.fiereu.openmmo.net.game.packets.battle.DuelRulesPacketCodec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DuelRulesPacketsTest :
    FunSpec({
      test("duel rule packets roundtrip") {
        val c = DuelRulesChallengePacket("Gary", DuelRules(50, 3, 30))
        DuelRulesChallengePacketCodec.decodeBytes(
            DuelRulesChallengePacketCodec.encodeToBytes(c)) shouldBe c
        val r = DuelRulesPacket("Ash", DuelRules(100, 6, 0))
        DuelRulesPacketCodec.decodeBytes(DuelRulesPacketCodec.encodeToBytes(r)) shouldBe r
        val t = BattleTurnTimerPacket(90, 2, 1, mustAct = false, autoPicked = true)
        BattleTurnTimerPacketCodec.decodeBytes(BattleTurnTimerPacketCodec.encodeToBytes(t)) shouldBe
            t
      }

      test("duel rule packets are registered in the expected direction") {
        GameProtocol.outgoingRegistration(Side.CLIENT, DuelRulesChallengePacket::class)
            ?.opcode shouldBe 0xEEu
        GameProtocol.outgoingRegistration(Side.SERVER, DuelRulesPacket::class)?.opcode shouldBe
            0xEEu
        GameProtocol.outgoingRegistration(Side.SERVER, BattleTurnTimerPacket::class)
            ?.opcode shouldBe 0xEFu
      }
    })
