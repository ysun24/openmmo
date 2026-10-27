package de.fiereu.openmmo.net.game

import de.fiereu.network.Direction
import de.fiereu.openmmo.common.test.decodeBytes
import de.fiereu.openmmo.common.test.encodeToBytes
import de.fiereu.openmmo.net.game.packets.contest.ContestActionPacket
import de.fiereu.openmmo.net.game.packets.contest.ContestActionPacketCodec
import de.fiereu.openmmo.net.game.packets.contest.ContestStatePacket
import de.fiereu.openmmo.net.game.packets.contest.ContestStatePacketCodec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** r5-contests: the JSON channel round-trips and sits on c2s 0xD5 / s2c 0xC6. */
class ContestPacketsTest :
    FunSpec({
      test("contest packets round-trip, including non-ASCII text") {
        val a = ContestActionPacket("""{"t":"contest.move","slot":2}""")
        ContestActionPacketCodec.decodeBytes(ContestActionPacketCodec.encodeToBytes(a)) shouldBe a
        val s = ContestStatePacket("""{"k":"pokeblocks","message":"POKéBLOCK"}""")
        ContestStatePacketCodec.decodeBytes(ContestStatePacketCodec.encodeToBytes(s)) shouldBe s
      }

      test("registered on c2s 0xD5 and s2c 0xC6") {
        val regs = GameProtocol.registrations
        regs
            .first { it.opcode.toInt() == 0xD5 && it.direction == Direction.C2S }
            .type
            .simpleName shouldBe "ContestActionPacket"
        regs
            .first { it.opcode.toInt() == 0xC6 && it.direction == Direction.S2C }
            .type
            .simpleName shouldBe "ContestStatePacket"
      }
    })
