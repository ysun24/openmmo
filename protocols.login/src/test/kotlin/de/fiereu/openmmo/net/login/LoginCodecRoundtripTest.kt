package de.fiereu.openmmo.net.login

import de.fiereu.openmmo.common.test.decodeBytes
import de.fiereu.openmmo.common.test.encodeToBytes
import de.fiereu.openmmo.net.login.packets.JoinGameServerPacket
import de.fiereu.openmmo.net.login.packets.JoinGameServerPacketCodec
import de.fiereu.openmmo.net.login.packets.MfaResponsePacket
import de.fiereu.openmmo.net.login.packets.MfaResponsePacketCodec
import de.fiereu.openmmo.net.login.packets.RegisterRequestPacket
import de.fiereu.openmmo.net.login.packets.RegisterRequestPacketCodec
import de.fiereu.openmmo.net.login.packets.RegisterResponsePacket
import de.fiereu.openmmo.net.login.packets.RegisterResponsePacketCodec
import de.fiereu.openmmo.net.login.packets.RegisterResult
import de.fiereu.openmmo.net.login.packets.RequestGameServerListPacket
import de.fiereu.openmmo.net.login.packets.RequestGameServerListPacketCodec
import de.fiereu.openmmo.net.login.packets.ToSConfirmationPacket
import de.fiereu.openmmo.net.login.packets.ToSConfirmationPacketCodec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LoginCodecRoundtripTest :
    FunSpec({
      test("RequestGameServerListPacket has no payload") {
        RequestGameServerListPacketCodec.encodeToBytes(RequestGameServerListPacket()).size shouldBe
            0
      }

      test("JoinGameServerPacket roundtrips") {
        val pkt = JoinGameServerPacket(gameServerId = 0x07u)
        val bytes = JoinGameServerPacketCodec.encodeToBytes(pkt)
        bytes.size shouldBe 1
        JoinGameServerPacketCodec.decodeBytes(bytes) shouldBe pkt
      }

      test("ToSConfirmationPacket roundtrip") {
        val pkt = ToSConfirmationPacket(confirmationKey = 1)
        ToSConfirmationPacketCodec.decodeBytes(
            ToSConfirmationPacketCodec.encodeToBytes(pkt)) shouldBe pkt
      }

      test("MfaResponsePacket roundtrips a string") {
        val pkt = MfaResponsePacket(mfaCode = "123456")
        MfaResponsePacketCodec.decodeBytes(MfaResponsePacketCodec.encodeToBytes(pkt)) shouldBe pkt
      }

      test("RegisterRequestPacket roundtrips") {
        val pkt = RegisterRequestPacket("misty_2", "a".repeat(40), "ABCD-EFGH")
        RegisterRequestPacketCodec.decodeBytes(
            RegisterRequestPacketCodec.encodeToBytes(pkt)) shouldBe pkt
      }

      test("RegisterResponsePacket roundtrips every result and maps unknown ids to SYSTEM_ERROR") {
        for (r in RegisterResult.entries) {
          val pkt = RegisterResponsePacket(r)
          RegisterResponsePacketCodec.decodeBytes(
              RegisterResponsePacketCodec.encodeToBytes(pkt)) shouldBe pkt
        }
        RegisterResponsePacketCodec.decodeBytes(byteArrayOf(99)).result shouldBe
            RegisterResult.SYSTEM_ERROR
      }
    })
