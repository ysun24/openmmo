package de.fiereu.openmmo.net.game

import de.fiereu.openmmo.common.test.decodeBytes
import de.fiereu.openmmo.common.test.encodeToBytes
import de.fiereu.openmmo.net.game.packets.field.AcroTrick
import de.fiereu.openmmo.net.game.packets.field.FieldAction
import de.fiereu.openmmo.net.game.packets.field.FieldActionPacket
import de.fiereu.openmmo.net.game.packets.field.FieldActionPacketCodec
import de.fiereu.openmmo.net.game.packets.field.FieldAvatarPacket
import de.fiereu.openmmo.net.game.packets.field.FieldAvatarPacketCodec
import de.fiereu.openmmo.net.game.packets.field.TravelMode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** r4: the Acro Bike trick rides in FieldActionPacket ACRO and in FieldAvatarPacket's flags. */
class AcroTrickPacketsTest :
    FunSpec({
      test("every trick round-trips in the avatar flags without touching the other bits") {
        for (t in AcroTrick.entries) for (female in listOf(false, true)) {
          val p =
              FieldAvatarPacket(7L, TravelMode.ACRO_BIKE, running = true, female = female, acro = t)
          FieldAvatarPacketCodec.decodeBytes(FieldAvatarPacketCodec.encodeToBytes(p)) shouldBe p
        }
      }

      test("a rider without a trick encodes like before") {
        val old = FieldAvatarPacket(7L, TravelMode.ACRO_BIKE, running = false, female = true)
        val bytes = FieldAvatarPacketCodec.encodeToBytes(old)
        bytes.last().toInt() shouldBe 4
      }

      test("the ACRO action carries the trick in arg") {
        val p = FieldActionPacket(FieldAction.ACRO, AcroTrick.SIDE_JUMP.ordinal)
        FieldActionPacketCodec.decodeBytes(FieldActionPacketCodec.encodeToBytes(p)) shouldBe p
      }
    })
