package de.fiereu.openmmo.server.game.services.trade

import de.fiereu.openmmo.common.CharacterInfo
import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.PokemonMove
import de.fiereu.openmmo.common.enums.EVs
import de.fiereu.openmmo.common.enums.IVs
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.server.game.storage.EntityIdService
import de.fiereu.openmmo.server.game.storage.StoredCharacter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.LocalDateTime

private val ids = EntityIdService()

internal fun tradeMon(
    ownerId: Long,
    slot: Int,
    dexId: Int = 1,
    container: PokemonContainer = PokemonContainer.PARTY,
): Pokemon =
    Pokemon(
        id = ids.newMonsterId(),
        ownerId = ownerId,
        container = container,
        containerSlot = slot.toShort(),
        dexId = dexId,
        seed = 1,
        ot = "OT$ownerId",
        nickname = "",
        level = 5,
        hp = 20,
        xp = 100,
        eVs = EVs(),
        iVs = IVs(),
        moves = listOf(PokemonMove(33, 35)),
        isShiny = false,
        hasHiddenAbility = false,
        isAlpha = false,
        isSecret = false,
        isFatefulEncounter = false,
        isRaidEncounter = false,
        caughtAt = LocalDateTime.now(),
    )

internal fun trader(name: String, partyDex: List<Int>, money: Int = 1000): StoredCharacter {
  val id = ids.newCharacterId()
  val now = LocalDateTime.now()
  val info =
      CharacterInfo(
          id = id,
          name = name,
          namePrefix = "",
          userId = 1,
          rivalSex = 0,
          lastLogin = now,
          createdAt = now,
          money = money,
          permissions = 8,
          remainingSafariSteps = 0,
          remainingSafariBalls = 0,
          pcExtraSlots = 0,
          battleBoxExtraSlots = 0,
          templateAmount = 0,
          positionRegionId = 1,
          positionBankId = 1,
          positionMapId = 1,
          positionX = 1,
          positionY = 1,
          repelLeft = 0,
          repelItemId = 0,
          lureLeft = 0,
          lureItemId = 0,
      )
  return StoredCharacter(
      info = info,
      pokemon = partyDex.mapIndexed { i, dex -> tradeMon(id, i, dex) }.toMutableList(),
      pcStorage = mutableListOf(),
      items = mutableMapOf(5017 to 10),
  )
}

private fun SwapResult.ok(): SwapResult.Ok = this.shouldBeInstanceOf<SwapResult.Ok>()

private fun SwapResult.reason(): String = this.shouldBeInstanceOf<SwapResult.Rejected>().reason

class TradeSwapTest :
    FunSpec({
      test("monsters change owner, the giver's party closes up and the receiver appends") {
        val a = trader("Ash", listOf(1, 4, 7))
        val b = trader("Gary", listOf(25, 133))
        val given = a.pokemon[1]
        val back = b.pokemon[0]

        val r =
            TradeSwap.apply(
                    a, TradeOffer(monIds = listOf(given.id)), b, TradeOffer(listOf(back.id)))
                .ok()

        r.a.pokemon.map { it.dexId } shouldContainExactly listOf(1, 7, 25)
        r.a.pokemon.map { it.containerSlot.toInt() } shouldContainExactly listOf(0, 1, 2)
        r.a.pokemon.all { it.ownerId == a.info.id } shouldBe true
        r.b.pokemon.map { it.dexId } shouldContainExactly listOf(133, 4)
        r.b.pokemon.map { it.containerSlot.toInt() } shouldContainExactly listOf(0, 1)
        r.b.pokemon.last().id shouldBe given.id
        r.b.pokemon.last().ownerId shouldBe b.info.id
        // The original trainer travels with the monster.
        r.b.pokemon.last().ot shouldBe given.ot
      }

      test("a received monster goes to the pc when the party is full") {
        val a = trader("Ash", listOf(1, 2, 3, 4, 5, 6))
        a.pcStorage += tradeMon(a.info.id, 3, 150, PokemonContainer.PC)
        val b = trader("Gary", listOf(25, 26))
        val r = TradeSwap.apply(a, TradeOffer(), b, TradeOffer(listOf(b.pokemon[0].id))).ok()
        r.a.pokemon.size shouldBe 6
        val received = r.a.pcStorage.single { it.dexId == 25 }
        received.container shouldBe PokemonContainer.PC
        received.containerSlot shouldBe 4.toShort()
        received.ownerId shouldBe a.info.id
      }

      test("nobody may trade away their last party monster") {
        val a = trader("Ash", listOf(1))
        val b = trader("Gary", listOf(25))
        TradeSwap.apply(a, TradeOffer(listOf(a.pokemon[0].id)), b, TradeOffer(money = 5))
            .reason() shouldContain "last party monster"
      }

      test("giving the whole party is fine when a monster comes back") {
        val a = trader("Ash", listOf(1))
        val b = trader("Gary", listOf(25, 26))
        val r =
            TradeSwap.apply(
                    a, TradeOffer(listOf(a.pokemon[0].id)), b, TradeOffer(listOf(b.pokemon[1].id)))
                .ok()
        r.a.pokemon.map { it.dexId } shouldContainExactly listOf(26)
        r.a.pokemon.single().containerSlot shouldBe 0.toShort()
      }

      test("items and money move and empty stacks vanish") {
        val a = trader("Ash", listOf(1, 2), money = 500)
        val b = trader("Gary", listOf(25), money = 100)
        val r =
            TradeSwap.apply(
                    a,
                    TradeOffer(items = mapOf(5017 to 10), money = 200),
                    b,
                    TradeOffer(money = 50))
                .ok()
        r.a.items.containsKey(5017) shouldBe false
        r.b.items[5017] shouldBe 20
        r.a.info.money shouldBe 350
        r.b.info.money shouldBe 250
      }

      test("offers the owner cannot back are refused") {
        val a = trader("Ash", listOf(1, 2), money = 100)
        val b = trader("Gary", listOf(25))
        val stranger = tradeMon(b.info.id, 0)
        TradeSwap.apply(a, TradeOffer(listOf(stranger.id)), b, TradeOffer()).reason() shouldContain
            "no longer has"
        TradeSwap.apply(a, TradeOffer(items = mapOf(5017 to 11)), b, TradeOffer())
            .reason() shouldContain "does not hold"
        TradeSwap.apply(a, TradeOffer(items = mapOf(5017 to -1)), b, TradeOffer())
            .reason() shouldContain "bad quantity"
        TradeSwap.apply(a, TradeOffer(money = 101), b, TradeOffer()).reason() shouldContain
            "does not have"
        TradeSwap.apply(a, TradeOffer(money = -1), b, TradeOffer()).reason() shouldContain
            "negative"
        val twice = a.pokemon[0].id
        TradeSwap.apply(a, TradeOffer(listOf(twice, twice)), b, TradeOffer()).reason() shouldContain
            "twice"
        TradeSwap.apply(a, TradeOffer(), b, TradeOffer()).reason() shouldContain "Nothing"
      }

      test("a pc monster can be offered and leaves the giver's pc") {
        val a = trader("Ash", listOf(1, 2))
        val boxed = tradeMon(a.info.id, 0, 150, PokemonContainer.PC)
        a.pcStorage += boxed
        val b = trader("Gary", listOf(25))
        val ok = TradeSwap.apply(a, TradeOffer(listOf(boxed.id)), b, TradeOffer())
        ok.shouldBeInstanceOf<SwapResult.Ok>()
        ok.a.pcStorage shouldBe emptyList()
        ok.a.pokemon.map { it.dexId } shouldBe listOf(1, 2)
        ok.b.pokemon.map { it.dexId } shouldBe listOf(25, 150)
        ok.b.pokemon.last().ownerId shouldBe b.info.id
      }

      test("a pc monster someone else owns is refused") {
        val a = trader("Ash", listOf(1, 2))
        val b = trader("Gary", listOf(25))
        val foreign = tradeMon(b.info.id, 0, 150, PokemonContainer.PC)
        a.pcStorage += foreign
        TradeSwap.apply(a, TradeOffer(listOf(foreign.id)), b, TradeOffer()).reason() shouldContain
            "no longer has"
      }

      test("money that would overflow is refused") {
        val a = trader("Ash", listOf(1, 2), money = Int.MAX_VALUE)
        val b = trader("Gary", listOf(25), money = 10)
        TradeSwap.apply(a, TradeOffer(), b, TradeOffer(money = 10)).reason() shouldContain
            "more money"
      }
    })
