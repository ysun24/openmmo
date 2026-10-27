package de.fiereu.openmmo.net.game

import de.fiereu.openmmo.common.test.decodeBytes
import de.fiereu.openmmo.common.test.encodeToBytes
import de.fiereu.openmmo.net.game.packets.battle.BattleFieldStatePacket
import de.fiereu.openmmo.net.game.packets.battle.BattleFieldStatePacketCodec
import de.fiereu.openmmo.net.game.packets.battle.BattleMonBlock
import de.fiereu.openmmo.net.game.packets.battle.BattleOpponentBlock
import de.fiereu.openmmo.net.game.packets.battle.OpposingSide
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** r3-present: the shiny flag rides in the byte after the gender of both battle mon blocks. */
class BattleShinyFlagTest :
    FunSpec({
      fun packet(meShiny: Boolean, foeShiny: Boolean) =
          BattleFieldStatePacket(
              playerName = "May",
              playerId = 0x19000L,
              playerAppearance = ByteArray(BattleFieldStatePacket.APPEARANCE_SIZE),
              background = 0,
              opposing = OpposingSide.WILD,
              trainerId = 0,
              playerParty =
                  listOf(
                      BattleMonBlock(
                          slot = 0,
                          entityId = 0x123C000L,
                          species = 258,
                          level = 5,
                          gender = 0,
                          abilityId = 67,
                          maxHp = 20,
                          currentHp = 20,
                          movesPresent = true,
                          moveIds = listOf(33, 45, 0, 0),
                          shiny = meShiny)),
              activeSlot = 0,
              opponentParty =
                  listOf(
                      BattleOpponentBlock(
                          slot = 0,
                          revealed = true,
                          entityId = 0x456C000L,
                          species = 263,
                          level = 3,
                          maxHp = 14,
                          currentHp = 14,
                          shiny = foeShiny)),
              opponentActiveSlot = 0,
          )

      test("round-trips the shiny flags of both sides") {
        for (me in listOf(false, true)) for (foe in listOf(false, true)) {
          val p = packet(me, foe)
          val d =
              BattleFieldStatePacketCodec.decodeBytes(BattleFieldStatePacketCodec.encodeToBytes(p))
          d.playerParty[0].shiny shouldBe me
          d.opponentParty[0].shiny shouldBe foe
          d shouldBe p
        }
      }

      test("a non-shiny block encodes exactly like before (the byte was padding)") {
        val a = BattleFieldStatePacketCodec.encodeToBytes(packet(false, false))
        val b = BattleFieldStatePacketCodec.encodeToBytes(packet(true, true))
        a.size shouldBe b.size
        a.indices.count { a[it] != b[it] } shouldBe 2
      }
    })
