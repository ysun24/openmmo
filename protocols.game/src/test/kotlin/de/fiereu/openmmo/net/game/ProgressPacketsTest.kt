package de.fiereu.openmmo.net.game

import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.PokemonMove
import de.fiereu.openmmo.common.enums.EVs
import de.fiereu.openmmo.common.enums.IVs
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.common.test.decodeBytes
import de.fiereu.openmmo.common.test.encodeToBytes
import de.fiereu.openmmo.net.game.packets.progress.DaycareView
import de.fiereu.openmmo.net.game.packets.progress.DecorStack
import de.fiereu.openmmo.net.game.packets.progress.LearnPrompt
import de.fiereu.openmmo.net.game.packets.progress.MonExtra
import de.fiereu.openmmo.net.game.packets.progress.ProgressAction
import de.fiereu.openmmo.net.game.packets.progress.ProgressActionPacket
import de.fiereu.openmmo.net.game.packets.progress.ProgressActionPacketCodec
import de.fiereu.openmmo.net.game.packets.progress.ProgressStatePacket
import de.fiereu.openmmo.net.game.packets.progress.ProgressStatePacketCodec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

class ProgressPacketsTest :
    FunSpec({
      val mon =
          Pokemon(
              id = 0x2222C000L,
              ownerId = 0x55559000L,
              // The monster codec does not carry the container; it reads back as PARTY.
              container = PokemonContainer.PARTY,
              containerSlot = 1,
              dexId = 25,
              seed = 7,
              ot = "Ash",
              nickname = "",
              level = 20,
              hp = 40,
              xp = 8000,
              eVs = EVs(),
              iVs = IVs(),
              moves = List(4) { PokemonMove(if (it == 0) 84 else 0, if (it == 0) 30 else 0) },
              isShiny = false,
              hasHiddenAbility = false,
              isAlpha = false,
              isSecret = false,
              isFatefulEncounter = false,
              isRaidEncounter = false,
              caughtAt = LocalDateTime.of(2026, 1, 2, 3, 4, 5),
          )

      test("the progress state round-trips, bitsets included") {
        val p =
            ProgressStatePacket(
                seen = ProgressStatePacket.bits(listOf(1, 25, 386)),
                caught = ProgressStatePacket.bits(listOf(25)),
                badges = 0x0103,
                playSeconds = 4000,
                registeredItem = 5450,
                extras = listOf(MonExtra(9, 8, 3)),
                daycare = DaycareView(2, listOf(mon), listOf(300), listOf(2), true),
                decorations = listOf(DecorStack(1, 2)),
                decorShop = listOf(1, 2, 3),
                learn = LearnPrompt(9, 73),
                permissions = 0x308,
                region = 1,
            )
        val back = ProgressStatePacketCodec.decodeBytes(ProgressStatePacketCodec.encodeToBytes(p))
        back shouldBe p
        back.isSeen(386) shouldBe true
        back.isCaught(1) shouldBe false
        ProgressStatePacket.ids(back.seen) shouldBe listOf(1, 25, 386)
      }

      test("no learn prompt round-trips as null") {
        val p =
            ProgressStatePacket(
                ByteArray(0),
                ByteArray(0),
                0,
                0,
                0,
                emptyList(),
                DaycareView(0, emptyList(), emptyList(), emptyList(), false),
                emptyList(),
                emptyList(),
                null,
                0,
                0)
        ProgressStatePacketCodec.decodeBytes(ProgressStatePacketCodec.encodeToBytes(p))
            .learn shouldBe null
      }

      test("actions round-trip with their wire ids") {
        val a = ProgressActionPacket(ProgressAction.USE_ON_MOVE, 5038, 0x1234C000L, 2, -1)
        ProgressActionPacketCodec.decodeBytes(ProgressActionPacketCodec.encodeToBytes(a)) shouldBe a
        ProgressAction.of(7) shouldBe ProgressAction.DECOR_BUY
      }
    })
