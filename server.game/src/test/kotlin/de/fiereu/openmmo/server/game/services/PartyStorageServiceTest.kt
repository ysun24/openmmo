package de.fiereu.openmmo.server.game.services

import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.maps.MapManager
import de.fiereu.openmmo.net.game.packets.SlotSwap
import de.fiereu.openmmo.server.game.services.PartyStorageService.Companion.ANY_SLOT
import de.fiereu.openmmo.server.game.services.PartyStorageService.Companion.PARTY
import de.fiereu.openmmo.server.game.services.PartyStorageService.Companion.PC
import de.fiereu.openmmo.server.game.services.PartyStorageService.Result
import de.fiereu.openmmo.server.game.storage.CharacterStore
import de.fiereu.openmmo.server.game.storage.EntityIdService
import de.fiereu.openmmo.server.game.testsupport.FakeCharacterRepository
import de.fiereu.openmmo.server.game.testsupport.battleService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class PartyStorageServiceTest :
    FunSpec({
      val store =
          CharacterStore(
              FakeCharacterRepository(), EntityIdService(), CoroutineScope(Dispatchers.Unconfined))
      val service = PartyStorageService(store, MapManager(), battleService(store))
      val a = partyMon(1, 0, hp = 10)
      val b = partyMon(1, 1, hp = 10)
      val c = partyMon(1, 2, hp = 0)
      val boxed = partyMon(1, 0, hp = 10).copy(container = PokemonContainer.PC, containerSlot = 7)

      fun swap(st: Byte, s: Int, dt: Byte, d: Int) = SlotSwap(st, s.toShort(), dt, d.toShort())

      test("the party reorders anywhere and slots are renumbered") {
        val r = service.apply(listOf(a, b, c), emptyList(), swap(PARTY, 0, PARTY, 2), atPc = false)
        r.shouldBeInstanceOf<Result.Moved>()
        r.party.map { it.id } shouldBe listOf(c.id, b.id, a.id)
        r.party.map { it.containerSlot.toInt() } shouldBe listOf(0, 1, 2)
      }

      test("the PC is only reachable in a Pokemon Center") {
        service
            .apply(listOf(a, b), emptyList(), swap(PARTY, 1, PC, ANY_SLOT), atPc = false)
            .shouldBeInstanceOf<Result.Refused>()
      }

      test("depositing moves the monster into the first free PC slot") {
        val r =
            service.apply(
                listOf(a, b),
                listOf(boxed.copy(containerSlot = 0)),
                swap(PARTY, 1, PC, ANY_SLOT),
                atPc = true)
        r.shouldBeInstanceOf<Result.Moved>()
        r.party.map { it.id } shouldBe listOf(a.id)
        r.pc.map { it.id to it.containerSlot.toInt() } shouldBe listOf(boxed.id to 0, b.id to 1)
        r.pc.all { it.container == PokemonContainer.PC } shouldBe true
      }

      test("the last monster that can fight stays in the party") {
        service
            .apply(listOf(a, c), emptyList(), swap(PARTY, 0, PC, ANY_SLOT), atPc = true)
            .shouldBeInstanceOf<Result.Refused>()
        service
            .apply(listOf(a), emptyList(), swap(PARTY, 0, PC, ANY_SLOT), atPc = true)
            .shouldBeInstanceOf<Result.Refused>()
      }

      test("withdrawing appends to the party, and a full party refuses") {
        val r = service.apply(listOf(a), listOf(boxed), swap(PC, 7, PARTY, ANY_SLOT), atPc = true)
        r.shouldBeInstanceOf<Result.Moved>()
        r.party.map { it.id } shouldBe listOf(a.id, boxed.id)
        r.party[1].container shouldBe PokemonContainer.PARTY
        r.pc shouldBe emptyList()
        val six = List(6) { partyMon(1, it, hp = 5) }
        service
            .apply(six, listOf(boxed), swap(PC, 7, PARTY, ANY_SLOT), atPc = true)
            .shouldBeInstanceOf<Result.Refused>()
      }

      test("withdrawing onto a party slot swaps the two") {
        val r = service.apply(listOf(a, b), listOf(boxed), swap(PC, 7, PARTY, 1), atPc = true)
        r.shouldBeInstanceOf<Result.Moved>()
        r.party.map { it.id } shouldBe listOf(a.id, boxed.id)
        r.pc.map { it.id to it.containerSlot.toInt() } shouldBe listOf(b.id to 7)
      }
    })
