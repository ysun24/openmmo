package de.fiereu.openmmo.server.game.storage

import de.fiereu.openmmo.common.enums.CharacterGender
import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.server.game.testsupport.FakeCharacterRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * The character-select list used to cache whatever the list query returned. A market sale that
 * committed (through [CharacterStore.withExternalWrite]) after that query but before the cache
 * insert left the old balance cached until the next relogin.
 */
class CharacterListRaceTest :
    FunSpec({
      test("a sale committing while the list loads does not leave a stale balance cached") {
        runBlocking {
          val listRead = CompletableDeferred<Unit>()
          val release = CompletableDeferred<Unit>()
          val backing = FakeCharacterRepository()
          val repo =
              object : CharacterRepository by backing {
                override suspend fun loadByUser(userId: Int): List<StoredCharacter> {
                  val snapshot = backing.loadByUser(userId)
                  listRead.complete(Unit)
                  release.await()
                  return snapshot
                }
              }
          val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
          val writer = CharacterStore(repo, EntityIdService(), scope)
          val created = writer.createCharacter(7, "Seller", CharacterGender.MALE, Region.HOENN)
          val id = created.info.id
          // A fresh store, as after a restart: nothing cached.
          val store = CharacterStore(repo, EntityIdService(), scope)

          val list = async(Dispatchers.Default) { store.getCharactersByUser(7) }
          listRead.await()
          // The sale: credited in the database while the list query is in flight.
          store.withExternalWrite(listOf(id)) {
            backing.saveChanges(
                null, created.copy(info = created.info.copy(money = created.info.money + 1500)))
          }
          release.complete(Unit)

          list.await().single().info.money shouldBe created.info.money + 1500
          store.getCharacter(id)!!.info.money shouldBe created.info.money + 1500
          scope.cancel()
        }
      }
    })
