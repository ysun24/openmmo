package de.fiereu.openmmo.server.game.script

import de.fiereu.openmmo.common.enums.CharacterGender
import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.dialog.generated.kanto.Misc
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.net.game.packets.PokemonContainerPacket
import de.fiereu.openmmo.net.game.packets.ShopCatalogPacket
import de.fiereu.openmmo.net.game.packets.dialog.DialogActionPacket
import de.fiereu.openmmo.net.game.packets.dialog.DialogActionResponsePacket
import de.fiereu.openmmo.net.game.packets.dialog.TextPokemonSpeciesArg
import de.fiereu.openmmo.net.game.packets.dialog.TextStringArg
import de.fiereu.openmmo.server.game.script.generated.GeneratedScripts
import de.fiereu.openmmo.server.game.session.PENDING_DIALOG
import de.fiereu.openmmo.server.game.session.PENDING_DIALOG_RESPONSE
import de.fiereu.openmmo.server.game.session.SCRIPT_SCOPE
import de.fiereu.openmmo.server.game.storage.CharacterStore
import de.fiereu.openmmo.server.game.storage.EntityIdService
import de.fiereu.openmmo.server.game.testsupport.FakeCharacterRepository
import de.fiereu.openmmo.server.game.testsupport.FakeSession
import de.fiereu.openmmo.server.game.testsupport.scriptRunner
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalCoroutinesApi::class)
class ContentScriptsTest :
    FunSpec({
      val items = ItemRegistry()

      suspend fun TestScope.setup(region: Region): Triple<CharacterStore, FakeSession, Long> {
        val store = CharacterStore(FakeCharacterRepository(), EntityIdService(), backgroundScope)
        val charId = store.createCharacter(1, "Red", CharacterGender.MALE, region).info.id
        val session = FakeSession(characterId = charId)
        session.attributes[SCRIPT_SCOPE] = backgroundScope
        session.state().regionId = region.wireValue.toInt()
        return Triple(store, session, charId)
      }

      fun TestScope.run(store: CharacterStore, session: FakeSession, label: String) {
        val script = GeneratedScripts.byLabel[label].shouldNotBeNull()
        scriptRunner(store).run(session, session.state(), script, entityId = 77)
        runCurrent()
      }

      /** Press A on the open box. */
      fun TestScope.advance(session: FakeSession) {
        session.attributes.remove(PENDING_DIALOG).shouldNotBeNull().complete(Unit)
        runCurrent()
      }

      fun TestScope.answer(session: FakeSession, yes: Boolean) {
        session.attributes
            .remove(PENDING_DIALOG_RESPONSE)
            .shouldNotBeNull()
            .complete(DialogActionResponsePacket(0, if (yes) 1 else 0))
        runCurrent()
      }

      fun FakeSession.shelf(): List<Int> =
          sent.filterIsInstance<ShopCatalogPacket>().single().items.map { it.itemId.toInt() }

      test("a Kanto clerk greets, then opens the decomp's stock") {
        runTest {
          val (store, session, _) = setup(Region.KANTO)
          run(store, session, "PewterCity_Mart_EventScript_Clerk")
          session.sent.filterIsInstance<DialogActionPacket>().single().textId shouldBe
              Misc.Text_MayIHelpYou.textId
          advance(session)
          session.shelf() shouldContainExactly
              listOf(
                      Items.POKE_BALL,
                      Items.POTION,
                      Items.ANTIDOTE,
                      Items.PARLYZ_HEAL,
                      Items.AWAKENING,
                      Items.BURN_HEAL,
                      Items.ESCAPE_ROPE,
                      Items.REPEL)
                  .map(items::idOf)
        }
      }

      test("a Hoenn clerk sells more once the story flag is set") {
        runTest {
          val (store, session, charId) = setup(Region.HOENN)
          run(store, session, "OldaleTown_Mart_EventScript_Clerk")
          advance(session)
          val basic = session.shelf()
          basic shouldContain items.idOf(Items.POTION)

          store.setStoryFlag(charId, "hoenn/FLAG_ADVENTURE_STARTED")
          session.sent.clear()
          run(store, session, "OldaleTown_Mart_EventScript_Clerk")
          advance(session)
          val expanded = session.shelf()
          (expanded.size > basic.size) shouldBe true
          expanded shouldContain items.idOf(Items.POKE_BALL)
        }
      }

      test("a Kanto nurse heals the party after a yes") {
        runTest {
          val (store, session, charId) = setup(Region.KANTO)
          run(store, session, "ViridianCity_PokemonCenter_1F_EventScript_Nurse")
          session.sent.filterIsInstance<DialogActionPacket>().last().textId shouldBe
              Misc.Text_WelcomeWantToHealPkmn.textId
          answer(session, yes = true)
          advance(session) // I'll take your Pokemon
          session.sent.filterIsInstance<PokemonContainerPacket>().size shouldBe 1
          advance(session) // restored
          advance(session) // we hope to see you again
          store.getCharacter(charId).shouldNotBeNull()
        }
      }

      test("every box carries the rival's name, and Oak names the starter") {
        runTest {
          val (store, session, _) = setup(Region.KANTO)
          val script = Script { ctx ->
            ctx.bufferSpeciesName(1, 4)
            ctx.bufferItemName(2, Items.POTION)
            ctx.say(Misc.Text_MayIHelpYou)
          }
          scriptRunner(store).run(session, session.state(), script, entityId = 1)
          runCurrent()
          session.sent.filterIsInstance<DialogActionPacket>().single().messageArgs shouldBe
              listOf(
                  TextPokemonSpeciesArg(1, 1, 4),
                  TextStringArg(2, "Potion"),
                  TextStringArg(TextStringArg.RIVAL, "GREEN"),
              )
        }
      }

      test("Kanto's decomp-only lines exist in the client's extra table with the same ids") {
        val root = File("..").absoluteFile
        val extra =
            Json.parseToJsonElement(File(root, "codegen/dialog/kanto.extra.json").readText())
                .jsonObject
                .getValue("lines")
                .jsonArray
                .associate {
                  it.jsonObject.getValue("label").jsonPrimitive.content to
                      it.jsonObject.getValue("id").jsonPrimitive.int
                }
        extra["Text_WelcomeWantToHealPkmn"] shouldBe Misc.Text_WelcomeWantToHealPkmn.textId
        extra["Text_RestoredPkmnToFullHealth"] shouldBe Misc.Text_RestoredPkmnToFullHealth.textId
      }
    })
