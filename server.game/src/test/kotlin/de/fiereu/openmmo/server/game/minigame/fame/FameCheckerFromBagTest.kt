package de.fiereu.openmmo.server.game.minigame.fame

import de.fiereu.openmmo.common.enums.CharacterGender
import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.minigame.InMemoryMinigameRepository
import de.fiereu.openmmo.server.game.minigame.MinigameSave
import de.fiereu.openmmo.server.game.services.StoryService
import de.fiereu.openmmo.server.game.storage.CharacterStore
import de.fiereu.openmmo.server.game.storage.EntityIdService
import de.fiereu.openmmo.server.game.testsupport.FakeCharacterRepository
import de.fiereu.openmmo.server.game.testsupport.FakeSession
import de.fiereu.openmmo.story.generated.kanto.KantoFlags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class FameCheckerFromBagTest :
    FunSpec({
      test("the FAME CHECKER from the bag (or SELECT) opens the Fame Checker; other items pass") {
        runTest {
          val store = CharacterStore(FakeCharacterRepository(), EntityIdService(), backgroundScope)
          val id = store.createCharacter(1, "Red", CharacterGender.MALE, Region.KANTO).info.id
          val hook =
              FameCheckerFromBag(
                  FameCheckerService(
                      MinigameSave(InMemoryMinigameRepository()), StoryService(store)))
          val session = FakeSession(id)
          fun lastFame() =
              session.sent
                  .filterIsInstance<
                      de.fiereu.openmmo.net.game.packets.minigame.MinigameStatePacket>()
                  .last()

          hook.tryUse(session, session.state(), Items.WAILMER_PAIL) shouldBe false
          session.sent.size shouldBe 0

          hook.tryUse(session, session.state(), Items.FAME_CHECKER) shouldBe true
          lastFame().topic shouldBe "fame"
          lastFame().body shouldContain "error"

          store.setStoryFlag(id, KantoFlags.FLAG_GOT_FAME_CHECKER)
          hook.tryUse(session, session.state(), Items.FAME_CHECKER) shouldBe true
          lastFame().body shouldContain "\"pick\""
        }
      }
    })
