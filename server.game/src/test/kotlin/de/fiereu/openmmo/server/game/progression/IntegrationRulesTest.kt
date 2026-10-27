package de.fiereu.openmmo.server.game.progression

import de.fiereu.openmmo.common.MonStatus
import de.fiereu.openmmo.common.enums.CharacterGender
import de.fiereu.openmmo.common.enums.MapType
import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.maps.MapDef
import de.fiereu.openmmo.maps.MapManager
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import de.fiereu.openmmo.server.game.services.partyMon
import de.fiereu.openmmo.server.game.storage.CharacterStore
import de.fiereu.openmmo.server.game.storage.EntityIdService
import de.fiereu.openmmo.server.game.testsupport.FakeCharacterRepository
import de.fiereu.openmmo.server.game.testsupport.FakeSession
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import javax.inject.Provider
import kotlin.random.Random
import kotlinx.coroutines.test.runTest

/** Friendship, heal locations and white-out money, field poison: the round-3 integration rules. */
class IntegrationRulesTest :
    FunSpec({
      val items = ItemRegistry()

      test("friendship follows AdjustFriendship's three bands, Soothe Bell and the cap") {
        Friendship.adjusted(70, FriendshipEvent.GROW_LEVEL, false, false) shouldBe 75
        Friendship.adjusted(150, FriendshipEvent.GROW_LEVEL, false, false) shouldBe 153
        Friendship.adjusted(220, FriendshipEvent.GROW_LEVEL, false, false) shouldBe 222
        // Soothe Bell: 150% of a positive change, rounded down; Luxury Ball adds one.
        Friendship.adjusted(150, FriendshipEvent.GROW_LEVEL, true, false) shouldBe 154
        Friendship.adjusted(70, FriendshipEvent.VITAMIN, false, true) shouldBe 76
        Friendship.adjusted(254, FriendshipEvent.EV_BERRY, false, false) shouldBe 255
        Friendship.adjusted(3, FriendshipEvent.FAINT_FIELD_PSN, true, false) shouldBe 0
        val mon = partyMon(1, 0, 10).copy(friendship = 70, heldItem = items.idOf(Items.SOOTHE_BELL))
        Friendship.adjust(mon, FriendshipEvent.GROW_LEVEL, items).friendship shouldBe 77
        // Walking passes its 50% gate only on a "false" coin.
        val walks =
            (0 until 200).count {
              Friendship.adjust(mon.copy(heldItem = 0), FriendshipEvent.WALKING, items, Random(it))
                  .friendship == 71
            }
        (walks in 60..140) shouldBe true
        Friendship.adjust(mon.copy(isEgg = true), FriendshipEvent.GROW_LEVEL, items) shouldBe
            mon.copy(isEgg = true)
      }

      test("white-out money: FireRed's level x badge table, Emerald's half") {
        HealLocations.moneyLoss(Region.KANTO, 10000, 0, 15) shouldBe 15 * 4 * 2
        HealLocations.moneyLoss(Region.KANTO, 10000, 8, 60) shouldBe 7200
        HealLocations.moneyLoss(Region.KANTO, 100, 3, 50) shouldBe 100
        HealLocations.moneyLoss(Region.HOENN, 3001, 2, 40) shouldBe 1501
      }

      test("a nurse's heal is recorded; FireRed respawns in front of her, Emerald at the town") {
        runTest {
          val store = CharacterStore(FakeCharacterRepository(), EntityIdService(), backgroundScope)
          val maps = MapManager()
          val heal = HealLocations(store, maps)
          val id = store.createCharacter(1, "Red", CharacterGender.MALE, Region.KANTO).info.id
          val center = maps.getMapByName(0, "ViridianCity_PokemonCenter_1F")!!
          val state =
              de.fiereu.openmmo.server.game.session.PlayerState(
                  userId = 1,
                  characterId = id,
                  regionId = 0,
                  bankId = center.bankId.toInt() and 0xFF,
                  mapId = center.mapId.toInt() and 0xFF)
          heal.recordHeal(state)
          val stored = store.getCharacter(id)!!
          heal.lastTown(stored, Region.KANTO)!!.mapName shouldBe "ViridianCity"
          val p = heal.respawn(stored, Region.KANTO, false)!!
          (p.mapId to (p.x to p.y)) shouldBe ((center.mapId.toInt() and 0xFF) to (7 to 4))
          // Nothing recorded in Hoenn: Littleroot's heal location, the girl's house for May.
          val h = heal.respawn(stored, Region.HOENN, true)!!
          maps.getMap(h.regionId, h.bankId, h.mapId)!!.name shouldBe "LittlerootTown"
          (h.x to h.y) shouldBe (14 to 9)
          // A heal elsewhere (a route) records nothing.
          heal.townOf(Region.KANTO, "Route1") shouldBe null
          heal.townOf(Region.HOENN, "PetalburgCity_PokemonCenter_1F")!!.mapName shouldBe
              "PetalburgCity"
        }
      }

      test("field poison: 1 HP every 4 steps, a faint cures it and costs friendship") {
        runTest {
          val store = CharacterStore(FakeCharacterRepository(), EntityIdService(), backgroundScope)
          val id = store.createCharacter(1, "Red", CharacterGender.MALE, Region.KANTO).info.id
          val sick = partyMon(id, 0, hp = 2).copy(status = MonStatus.POISON, friendship = 70)
          val fine = partyMon(id, 1, hp = 20)
          store.addPokemon(id, sick)
          store.addPokemon(id, fine)
          var whiteOuts = 0
          val effects =
              FieldStepEffects(
                  store,
                  items,
                  SpeciesRegistry(),
                  Provider { error("no white-out expected: ${++whiteOuts}") })
          val route = MapDef(0, 0, 0, mapType = MapType.ROUTE)
          val session = FakeSession(id)
          repeat(3) { effects.onStep(session, id, route) }
          store.getCharacter(id)!!.pokemon.first { it.id == sick.id }.hp.toInt() shouldBe 2
          effects.onStep(session, id, route)
          store.getCharacter(id)!!.pokemon.first { it.id == sick.id }.hp.toInt() shouldBe 1
          repeat(4) { effects.onStep(session, id, route) }
          val fainted = store.getCharacter(id)!!.pokemon.first { it.id == sick.id }
          fainted.hp.toInt() shouldBe 0
          fainted.status shouldBe MonStatus.NONE
          fainted.friendship shouldBe 65
          whiteOuts shouldBe 0
          // A secret base does not count poison steps.
          val base = MapDef(0, 0, 1, mapType = MapType.SECRET_BASE)
          store.updatePokemon(id, fine.copy(status = MonStatus.TOXIC))
          repeat(8) { effects.onStep(session, id, base) }
          store.getCharacter(id)!!.pokemon.first { it.id == fine.id }.hp.toInt() shouldBe 20
        }
      }
    })
