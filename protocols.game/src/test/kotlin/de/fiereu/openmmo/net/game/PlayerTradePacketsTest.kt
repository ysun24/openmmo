package de.fiereu.openmmo.net.game

import de.fiereu.network.Side
import de.fiereu.openmmo.common.test.decodeBytes
import de.fiereu.openmmo.common.test.encodeToBytes
import de.fiereu.openmmo.net.game.packets.trade.TradeItemView
import de.fiereu.openmmo.net.game.packets.trade.TradeMonView
import de.fiereu.openmmo.net.game.packets.trade.TradeOfferItemPacket
import de.fiereu.openmmo.net.game.packets.trade.TradeOfferItemPacketCodec
import de.fiereu.openmmo.net.game.packets.trade.TradeOfferView
import de.fiereu.openmmo.net.game.packets.trade.TradePhase
import de.fiereu.openmmo.net.game.packets.trade.TradeRequestPacket
import de.fiereu.openmmo.net.game.packets.trade.TradeRequestPacketCodec
import de.fiereu.openmmo.net.game.packets.trade.TradeStage
import de.fiereu.openmmo.net.game.packets.trade.TradeStatePacket
import de.fiereu.openmmo.net.game.packets.trade.TradeStatePacketCodec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PlayerTradePacketsTest :
    FunSpec({
      test("TradeStatePacket roundtrip with both offers") {
        val pkt =
            TradeStatePacket(
                phase = TradePhase.OPEN,
                partnerName = "Gary",
                mine =
                    TradeOfferView(
                        listOf(TradeMonView(2, 25, 12, "Sparky", true, 33)),
                        listOf(TradeItemView(5017, 3)),
                        150),
                theirs = TradeOfferView(),
                myStage = TradeStage.CONFIRMED,
                theirStage = TradeStage.READY,
                message = "hi",
            )
        TradeStatePacketCodec.decodeBytes(TradeStatePacketCodec.encodeToBytes(pkt)) shouldBe pkt
      }

      test("request and offer roundtrip") {
        val req = TradeRequestPacket("Gary")
        TradeRequestPacketCodec.decodeBytes(TradeRequestPacketCodec.encodeToBytes(req)) shouldBe req
        val offer = TradeOfferItemPacket(0, 5017, 3)
        TradeOfferItemPacketCodec.decodeBytes(
            TradeOfferItemPacketCodec.encodeToBytes(offer)) shouldBe offer
      }

      test("trade packets are registered in the expected direction") {
        GameProtocol.outgoingRegistration(Side.CLIENT, TradeRequestPacket::class)?.opcode shouldBe
            0x5Cu
        GameProtocol.outgoingRegistration(Side.CLIENT, TradeOfferItemPacket::class)?.opcode shouldBe
            0x5Du
        GameProtocol.outgoingRegistration(Side.SERVER, TradeStatePacket::class)?.opcode shouldBe
            0xECu
      }
    })
