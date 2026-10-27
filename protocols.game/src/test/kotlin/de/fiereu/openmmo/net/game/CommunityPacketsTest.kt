package de.fiereu.openmmo.net.game

import de.fiereu.bytecodec.Codec
import de.fiereu.network.Side
import de.fiereu.openmmo.common.test.decodeBytes
import de.fiereu.openmmo.common.test.encodeToBytes
import de.fiereu.openmmo.net.game.packets.social.ChatChannel
import de.fiereu.openmmo.net.game.packets.social.CommunityBody
import de.fiereu.openmmo.net.game.packets.social.CommunityPacket
import de.fiereu.openmmo.net.game.packets.social.CommunityPacketCodec
import de.fiereu.openmmo.net.game.packets.social.FriendView
import de.fiereu.openmmo.net.game.packets.social.GuildLogView
import de.fiereu.openmmo.net.game.packets.social.GuildMemberView
import de.fiereu.openmmo.net.game.packets.social.ListingMeta
import de.fiereu.openmmo.net.game.packets.social.MapRef
import de.fiereu.openmmo.net.game.packets.social.MarketQueryOp
import de.fiereu.openmmo.net.game.packets.social.MarketQueryPacket
import de.fiereu.openmmo.net.game.packets.social.MarketQueryPacketCodec
import de.fiereu.openmmo.net.game.packets.social.PricePoint
import de.fiereu.openmmo.net.game.packets.social.ProfileMon
import de.fiereu.openmmo.net.game.packets.social.RecentSale
import de.fiereu.openmmo.net.game.packets.social.SaleRecord
import de.fiereu.openmmo.net.game.packets.social.SocialOp
import de.fiereu.openmmo.net.game.packets.social.SocialRequestPacket
import de.fiereu.openmmo.net.game.packets.social.SocialRequestPacketCodec
import de.fiereu.openmmo.net.game.packets.social.TradeOfferPcMonPacket
import de.fiereu.openmmo.net.game.packets.social.TradeOfferPcMonPacketCodec
import de.fiereu.openmmo.net.game.packets.social.TradeRecord
import de.fiereu.openmmo.net.game.packets.trade.TradeMonView
import de.fiereu.openmmo.net.game.packets.trade.TradeOfferView
import de.fiereu.openmmo.net.game.packets.trade.TradePhase
import de.fiereu.openmmo.net.game.packets.trade.TradeStatePacket
import de.fiereu.openmmo.net.game.packets.trade.TradeStatePacketCodec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun <T> Codec<T>.roundtrip(v: T): T = decodeBytes(encodeToBytes(v))

class CommunityPacketsTest :
    FunSpec({
      val here = MapRef(1, 50, 9)
      val bodies: List<CommunityBody> =
          listOf(
              CommunityBody.Friends(
                  listOf(
                      FriendView("Gary", true, here, 1_790_000_000, true),
                      FriendView("Misty", false, MapRef.UNKNOWN, 0, false)),
                  listOf("Spammer")),
              CommunityBody.Profile(
                  "Gary",
                  true,
                  here,
                  "Rockets",
                  "RKT",
                  listOf(ProfileMon(25, 12, true), ProfileMon(386, 100, false)),
                  1_780_000_000,
                  friend = true,
                  blocked = false,
                  trades = 3),
              CommunityBody.Chat(ChatChannel.WHISPER, "Ash", "Gary", "héllo ✉", 1_790_000_123),
              CommunityBody.Guild(
                  true,
                  "Rockets",
                  "RKT",
                  "Meet at Celadon",
                  5,
                  listOf(
                      GuildMemberView("Ash", 5, true, here, 1),
                      GuildMemberView("Gary", 0, false, MapRef.UNKNOWN, 2)),
                  listOf(GuildLogView(3, "Ash founded the guild."))),
              CommunityBody.Guild(false),
              CommunityBody.GuildInvite("Ash", "Rockets", "RKT", 60),
              CommunityBody.ListingMetas(
                  listOf(ListingMeta(7, "Ash"), ListingMeta(Long.MAX_VALUE, ""))),
              CommunityBody.PriceHistory(
                  0,
                  25,
                  listOf(PricePoint(20_000, 100, 900, 450, 4)),
                  listOf(RecentSale(1_790_000_000, 450, 1))),
              CommunityBody.SalesHistory(listOf(SaleRecord(9, 1, 5017, 3, 300, 6, "Gary", 77))),
              CommunityBody.MarketInfo(20, 1, 30, 2, 168),
              CommunityBody.MarketOpen,
              CommunityBody.TradeHistory(
                  listOf(TradeRecord(5, "Gary", "PIKACHU Lv12", "500 money"))),
          )

      test("every community body survives a roundtrip") {
        for (b in bodies) CommunityPacketCodec.roundtrip(CommunityPacket(b)) shouldBe
            CommunityPacket(b)
      }

      test("c2s requests roundtrip") {
        for (op in SocialOp.entries) {
          val p = SocialRequestPacket(op, "Gary", "text", 3)
          SocialRequestPacketCodec.roundtrip(p) shouldBe p
        }
        for (op in MarketQueryOp.entries) {
          val p = MarketQueryPacket(op, 1, 5017)
          MarketQueryPacketCodec.roundtrip(p) shouldBe p
        }
        TradeOfferPcMonPacketCodec.roundtrip(TradeOfferPcMonPacket(41)) shouldBe
            TradeOfferPcMonPacket(41)
      }

      test("trade state carries pc monsters and the request expiry") {
        val p =
            TradeStatePacket(
                TradePhase.REQUEST_SENT,
                "Gary",
                mine = TradeOfferView(pcMons = listOf(TradeMonView(4, 19, 5, "", false, 20))),
                expiresInSeconds = 42)
        TradeStatePacketCodec.roundtrip(p) shouldBe p
      }

      test("registered in our reserved opcodes") {
        GameProtocol.outgoingRegistration(Side.CLIENT, SocialRequestPacket::class)?.opcode shouldBe
            0xFCu
        GameProtocol.outgoingRegistration(Side.CLIENT, MarketQueryPacket::class)?.opcode shouldBe
            0xFDu
        GameProtocol.outgoingRegistration(Side.CLIENT, TradeOfferPcMonPacket::class)
            ?.opcode shouldBe 0xFEu
        GameProtocol.outgoingRegistration(Side.SERVER, CommunityPacket::class)?.opcode shouldBe
            0xFDu
      }
    })
