package de.fiereu.openmmo.net.game

import de.fiereu.network.Side
import de.fiereu.openmmo.common.test.decodeBytes
import de.fiereu.openmmo.common.test.encodeToBytes
import de.fiereu.openmmo.net.game.packets.battle.BattleExtActionPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleExtActionPacketCodec
import de.fiereu.openmmo.net.game.packets.battle.BattleExtKind
import de.fiereu.openmmo.net.game.packets.battle.BattleExtPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleExtPacketCodec
import de.fiereu.openmmo.net.game.packets.battle.BattleTurnTimerPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleTurnTimerPacketCodec
import de.fiereu.openmmo.net.game.packets.battle.DuelRules
import de.fiereu.openmmo.net.game.packets.battle.DuelRulesChallengePacket
import de.fiereu.openmmo.net.game.packets.battle.DuelRulesChallengePacketCodec
import de.fiereu.openmmo.net.game.packets.battle.DuelRulesPacket
import de.fiereu.openmmo.net.game.packets.battle.DuelRulesPacketCodec
import de.fiereu.openmmo.net.game.packets.battle.EvolutionReplyPacket
import de.fiereu.openmmo.net.game.packets.battle.EvolutionReplyPacketCodec
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

      test("r3-battle: the doubles flag roundtrips and old senders read as 1v1") {
        val c = DuelRulesChallengePacket("Gary", DuelRules(0, 4, 0, doubles = true))
        DuelRulesChallengePacketCodec.decodeBytes(
            DuelRulesChallengePacketCodec.encodeToBytes(c)) shouldBe c
        val bytes = DuelRulesPacketCodec.encodeToBytes(DuelRulesPacket("Ash", DuelRules(50, 3, 30)))
        DuelRulesPacketCodec.decodeBytes(bytes.copyOf(bytes.size - 1)).rules shouldBe
            DuelRules(50, 3, 30)
      }

      test("r3-battle extension packets roundtrip and are registered") {
        val e = BattleExtPacket("""{"t":"state","é":1}""")
        BattleExtPacketCodec.decodeBytes(BattleExtPacketCodec.encodeToBytes(e)) shouldBe e
        val a = BattleExtActionPacket(1, BattleExtKind.MOVE, 89, 1, 0, 0)
        BattleExtActionPacketCodec.decodeBytes(BattleExtActionPacketCodec.encodeToBytes(a)) shouldBe
            a
        val i = BattleExtActionPacket(0, BattleExtKind.ITEM, 5017, -1, 2, 1234567890123L)
        BattleExtActionPacketCodec.decodeBytes(BattleExtActionPacketCodec.encodeToBytes(i)) shouldBe
            i
        val r = EvolutionReplyPacket(42L, false)
        EvolutionReplyPacketCodec.decodeBytes(EvolutionReplyPacketCodec.encodeToBytes(r)) shouldBe r
        GameProtocol.outgoingRegistration(Side.SERVER, BattleExtPacket::class)?.opcode shouldBe
            0xE6u
        GameProtocol.outgoingRegistration(Side.CLIENT, BattleExtActionPacket::class)
            ?.opcode shouldBe 0xF0u
        GameProtocol.outgoingRegistration(Side.CLIENT, EvolutionReplyPacket::class)?.opcode shouldBe
            0xF2u
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
