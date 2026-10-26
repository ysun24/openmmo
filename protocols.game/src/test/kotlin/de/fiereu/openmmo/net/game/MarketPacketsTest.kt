package de.fiereu.openmmo.net.game

import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.PokemonMove
import de.fiereu.openmmo.common.enums.EVs
import de.fiereu.openmmo.common.enums.IVs
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.common.test.decodeBytes
import de.fiereu.openmmo.common.test.encodeToBytes
import de.fiereu.openmmo.net.game.packets.gtl.GtlItemListing
import de.fiereu.openmmo.net.game.packets.gtl.GtlListKind
import de.fiereu.openmmo.net.game.packets.gtl.GtlPokemonListing
import de.fiereu.openmmo.net.game.packets.gtl.GtlSearchPagePacket
import de.fiereu.openmmo.net.game.packets.gtl.GtlSearchPagePacketCodec
import de.fiereu.openmmo.net.game.packets.market.MarketAction
import de.fiereu.openmmo.net.game.packets.market.MarketInventoryPacket
import de.fiereu.openmmo.net.game.packets.market.MarketInventoryPacketCodec
import de.fiereu.openmmo.net.game.packets.market.MarketItemStack
import de.fiereu.openmmo.net.game.packets.market.MarketResultPacket
import de.fiereu.openmmo.net.game.packets.market.MarketResultPacketCodec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

class MarketPacketsTest :
    FunSpec({
      val mon =
          Pokemon(
              id = 0x1234C000L,
              ownerId = 0x55559000L,
              container = PokemonContainer.PARTY,
              containerSlot = 1,
              dexId = 25,
              seed = 99,
              ot = "Ash",
              nickname = "Sparky",
              level = 12,
              hp = 33,
              xp = 1500,
              eVs = EVs(),
              iVs = IVs().also { it.atk = 31 },
              moves = List(4) { PokemonMove(if (it == 0) 84 else 0, if (it == 0) 30 else 0) },
              isShiny = false,
              hasHiddenAbility = false,
              isAlpha = false,
              isSecret = false,
              isFatefulEncounter = false,
              isRaidEncounter = false,
              caughtAt = LocalDateTime.of(2026, 1, 2, 3, 4, 5),
          )

      test("an own listings page mixing monsters and items round-trips") {
        val page =
            GtlSearchPagePacket(
                requestId = 3,
                listKind = GtlListKind.OWN_LISTINGS,
                page = 0,
                totalMatches = 2,
                listings =
                    listOf(
                        GtlPokemonListing(7, 500, 100, 200, 1, mon, List(6) { 10 }),
                        GtlItemListing(8, 90, 100, 200, 4, 13, 0),
                    ),
                quotes = null,
            )
        val decoded =
            GtlSearchPagePacketCodec.decodeBytes(GtlSearchPagePacketCodec.encodeToBytes(page))
        decoded.listings.size shouldBe 2
        (decoded.listings[0] as GtlPokemonListing).pokemon!!.dexId shouldBe 25
        (decoded.listings[0] as GtlPokemonListing).pokemon!!.ownerId shouldBe mon.ownerId
        decoded.listings[1] shouldBe page.listings[1]
      }

      test("MarketResultPacket round-trips") {
        val p = MarketResultPacket(MarketAction.SOLD, true, 42, "Sold Pikachu for 500.")
        MarketResultPacketCodec.decodeBytes(MarketResultPacketCodec.encodeToBytes(p)) shouldBe p
      }

      test("MarketInventoryPacket round-trips") {
        val p = MarketInventoryPacket(1234, listOf(mon), 3, listOf(MarketItemStack(13, 5)))
        val decoded =
            MarketInventoryPacketCodec.decodeBytes(MarketInventoryPacketCodec.encodeToBytes(p))
        decoded.money shouldBe 1234
        decoded.party.single().nickname shouldBe "Sparky"
        decoded.pcCount shouldBe 3.toShort()
        decoded.items shouldBe p.items
      }
    })
