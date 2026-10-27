package de.fiereu.openmmo.server.game.services.social

import de.fiereu.network.PacketEvent
import de.fiereu.openmmo.common.enums.CharacterGender
import de.fiereu.openmmo.common.enums.GuildRank
import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.net.game.packets.ChatMessagePacket
import de.fiereu.openmmo.net.game.packets.social.ChatChannel
import de.fiereu.openmmo.net.game.packets.social.CommunityBody
import de.fiereu.openmmo.net.game.packets.social.CommunityPacket
import de.fiereu.openmmo.net.game.packets.social.SocialOp
import de.fiereu.openmmo.net.game.packets.social.SocialRequestPacket
import de.fiereu.openmmo.server.game.services.GuildRules
import de.fiereu.openmmo.server.game.services.GuildService
import de.fiereu.openmmo.server.game.services.SocialService
import de.fiereu.openmmo.server.game.services.trade.tradeMon
import de.fiereu.openmmo.server.game.session.SessionRegistry
import de.fiereu.openmmo.server.game.storage.CharacterStore
import de.fiereu.openmmo.server.game.storage.EntityIdService
import de.fiereu.openmmo.server.game.storage.InMemorySocialRepository
import de.fiereu.openmmo.server.game.testsupport.FakeCharacterRepository
import de.fiereu.openmmo.server.game.testsupport.FakeSession
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

internal class SocialFixture(
    scope: CoroutineScope,
    val config: SocialConfig =
        SocialConfig(rates = RateKind.entries.associateWith { Rate(1000, 1) }),
    filter: ChatFilter = WordListChatFilter(emptyList()),
) {
  val charRepo = FakeCharacterRepository()
  val store = CharacterStore(charRepo, EntityIdService(), scope)
  val registry = SessionRegistry()
  val repo = InMemorySocialRepository()
  val directory = OnlineDirectory(registry, store)
  val blocks = BlockList(repo)
  val limiter = RateLimiter(config.rates)
  val guilds = GuildService(repo, directory, store, config, blocks, filter, scope)
  val social =
      SocialService(repo, directory, charRepo, blocks, guilds, config, limiter, filter, scope)

  private var nextUser = 1

  /** An online player standing on [mapId]. */
  suspend fun player(name: String, mapId: Int = 3, party: List<Int> = listOf(25)): Player {
    val created = store.createCharacter(nextUser++, name, CharacterGender.MALE, Region.HOENN)
    val id = created.info.id
    party.forEachIndexed { i, dex -> store.addPokemon(id, tradeMon(id, i, dex)) }
    store.updatePosition(id, 5, 5, bankId = 1, mapId = mapId.toByte())
    val session = FakeSession(id)
    registry.bindCharacter(session, id)
    repo.register(id, name, created.info.userId, created.info.createdAt)
    return Player(session, id, name, created.info.userId)
  }

  suspend fun req(p: Player, op: SocialOp, name: String = "", text: String = "", arg: Int = 0) =
      social.onRequest(PacketEvent(SocialRequestPacket(op, name, text, arg), p.session))

  suspend fun say(p: Player, channel: ChatChannel, text: String, to: String = "") =
      req(p, SocialOp.CHAT, to, text, channel.ordinal)
}

internal data class Player(
    val session: FakeSession,
    val id: Long,
    val name: String,
    val userId: Int
) {
  fun chat(): List<CommunityBody.Chat> =
      session.sent
          .filterIsInstance<CommunityPacket>()
          .map { it.body }
          .filterIsInstance<CommunityBody.Chat>()

  fun notices(): List<String> =
      session.sent.filterIsInstance<ChatMessagePacket>().map { it.message }

  fun friends(): CommunityBody.Friends =
      session.sent
          .filterIsInstance<CommunityPacket>()
          .map { it.body }
          .filterIsInstance<CommunityBody.Friends>()
          .last()

  fun guild(): CommunityBody.Guild =
      session.sent
          .filterIsInstance<CommunityPacket>()
          .map { it.body }
          .filterIsInstance<CommunityBody.Guild>()
          .last()
}

@OptIn(ExperimentalCoroutinesApi::class)
class SocialServiceTest :
    FunSpec({
      test("friends: add, mutual flag, online location, remove; unknown and self refused") {
        runTest {
          val fx = SocialFixture(backgroundScope)
          val ash = fx.player("Ash")
          val gary = fx.player("Gary", mapId = 7)
          fx.req(ash, SocialOp.FRIEND_ADD, "nobody")
          ash.notices().last() shouldBe "There is no trainer named nobody."
          fx.req(ash, SocialOp.FRIEND_ADD, "ash")
          ash.notices().last() shouldBe "You cannot add yourself."

          fx.req(ash, SocialOp.FRIEND_ADD, "gary")
          val f = ash.friends().friends.single()
          f.name shouldBe "Gary"
          f.online shouldBe true
          f.location.mapId shouldBe 7
          f.mutual shouldBe false
          gary.notices().last() shouldBe "Ash added you as a friend."
          fx.req(ash, SocialOp.FRIEND_ADD, "Gary")
          ash.notices().last() shouldBe "Gary already is your friend."

          fx.req(gary, SocialOp.FRIEND_ADD, "Ash")
          fx.req(ash, SocialOp.REFRESH)
          ash.friends().friends.single().mutual shouldBe true

          fx.req(ash, SocialOp.FRIEND_REMOVE, "Gary")
          ash.friends().friends.shouldBeEmpty()
        }
      }

      test("an offline friend shows offline with no location") {
        runTest {
          val fx = SocialFixture(backgroundScope)
          val ash = fx.player("Ash")
          val gary = fx.player("Gary")
          fx.req(ash, SocialOp.FRIEND_ADD, "Gary")
          fx.registry.unbindCharacter(gary.id)
          fx.req(ash, SocialOp.REFRESH)
          val f = ash.friends().friends.single()
          f.online shouldBe false
          f.location.known shouldBe false
        }
      }

      test("joining tells friends you are online, leaving that you went offline") {
        runTest {
          val fx = SocialFixture(backgroundScope)
          val ash = fx.player("Ash")
          val gary = fx.player("Gary")
          fx.req(gary, SocialOp.FRIEND_ADD, "Ash")
          fx.social.onJoin(ash.session, ash.id)
          gary.notices() shouldContain "Ash is online."
          fx.registry.unbindCharacter(ash.id)
          fx.social.onDisconnect(ash.id, ash.userId)
          runCurrent()
          gary.notices() shouldContain "Ash went offline."
          gary.friends().friends.single().online shouldBe false
        }
      }

      test("the join announcement runs once per session, not on every warp") {
        runTest {
          val fx = SocialFixture(backgroundScope)
          val ash = fx.player("Ash")
          val gary = fx.player("Gary")
          fx.req(gary, SocialOp.FRIEND_ADD, "Ash")
          repeat(3) { fx.social.sendFriendList(ash.session) }
          runCurrent()
          gary.notices().count { it == "Ash is online." } shouldBe 1
        }
      }

      test("block: hides chat and whispers, drops the friendship, refuses re-adding") {
        runTest {
          val fx = SocialFixture(backgroundScope)
          val ash = fx.player("Ash")
          val gary = fx.player("Gary")
          fx.req(gary, SocialOp.FRIEND_ADD, "Ash")
          fx.req(gary, SocialOp.BLOCK, "Ash")
          gary.friends().blocked shouldContainExactly listOf("Ash")
          gary.friends().friends.shouldBeEmpty()
          fx.req(gary, SocialOp.FRIEND_ADD, "Ash")
          gary.notices().last() shouldBe "Unblock Ash first."

          fx.say(ash, ChatChannel.GLOBAL, "hello all")
          ash.chat().single().text shouldBe "hello all"
          gary.chat().shouldBeEmpty()
          fx.say(ash, ChatChannel.WHISPER, "psst", to = "Gary")
          ash.chat().last().channel shouldBe ChatChannel.SYSTEM
          ash.chat().last().text shouldBe "Gary is not accepting messages."
          gary.chat().shouldBeEmpty()

          fx.req(gary, SocialOp.UNBLOCK, "Ash")
          fx.say(ash, ChatChannel.GLOBAL, "again")
          gary.chat().single().text shouldBe "again"
        }
      }

      test("local chat stays on the map, global and trade reach everyone") {
        runTest {
          val fx = SocialFixture(backgroundScope)
          val ash = fx.player("Ash", mapId = 3)
          val gary = fx.player("Gary", mapId = 3)
          val misty = fx.player("Misty", mapId = 9)
          fx.say(ash, ChatChannel.LOCAL, "hi room")
          gary.chat().single().let {
            it.channel shouldBe ChatChannel.LOCAL
            it.from shouldBe "Ash"
          }
          misty.chat().shouldBeEmpty()
          fx.say(ash, ChatChannel.TRADE, "WTS potion")
          misty.chat().single().channel shouldBe ChatChannel.TRADE
          fx.say(ash, ChatChannel.GLOBAL, "hi world")
          misty.chat().last().text shouldBe "hi world"
        }
      }

      test("whispers reach only the target and echo to the sender with the target name") {
        runTest {
          val fx = SocialFixture(backgroundScope)
          val ash = fx.player("Ash")
          val gary = fx.player("Gary")
          val misty = fx.player("Misty")
          fx.say(ash, ChatChannel.WHISPER, "secret", to = "gary")
          gary.chat().single().let {
            it.channel shouldBe ChatChannel.WHISPER
            it.from shouldBe "Ash"
            it.to shouldBe "Gary"
          }
          ash.chat().single().to shouldBe "Gary"
          misty.chat().shouldBeEmpty()
          fx.say(ash, ChatChannel.WHISPER, "x", to = "Brock")
          ash.chat().last().text shouldBe "Brock is not online."
        }
      }

      test("guild chat needs a guild and reaches only members") {
        runTest {
          val fx = SocialFixture(backgroundScope)
          val ash = fx.player("Ash")
          val gary = fx.player("Gary")
          val misty = fx.player("Misty")
          fx.say(ash, ChatChannel.GUILD, "anyone?")
          ash.chat().last().text shouldBe "You are not in a guild."
          fx.store.addMoney(ash.id, 100_000)
          fx.req(ash, SocialOp.GUILD_CREATE, "Pallet Pals", "PAL")
          fx.req(ash, SocialOp.GUILD_INVITE, "Gary")
          fx.req(gary, SocialOp.GUILD_ANSWER, arg = 1)
          fx.say(gary, ChatChannel.GUILD, "for the guild")
          ash.chat().last().text shouldBe "for the guild"
          ash.chat().last().channel shouldBe ChatChannel.GUILD
          misty.chat().shouldBeEmpty()
        }
      }

      test("chat is cleaned, length limited, rate limited and filtered") {
        runTest {
          val fx =
              SocialFixture(
                  backgroundScope,
                  SocialConfig(chatMaxLength = 10, rates = mapOf(RateKind.CHAT to Rate(2, 60))),
                  WordListChatFilter(listOf("darn")))
          val ash = fx.player("Ash")
          val gary = fx.player("Gary")
          fx.say(ash, ChatChannel.GLOBAL, "this is far too long")
          ash.chat().last().text shouldContain "limited to 10"
          fx.say(ash, ChatChannel.GLOBAL, "  oh\u0007  DARN ")
          gary.chat().single().text shouldBe "oh ****"
          fx.say(ash, ChatChannel.GLOBAL, "two")
          fx.say(ash, ChatChannel.GLOBAL, "three")
          ash.chat().last().text shouldBe "You are sending messages too fast."
          gary.chat().map { it.text } shouldContainExactly listOf("oh ****", "two")
          // Blank lines are dropped without using up the budget.
          fx.say(ash, ChatChannel.GLOBAL, "   ")
        }
      }

      test("social requests are rate limited per account") {
        runTest {
          val fx =
              SocialFixture(
                  backgroundScope, SocialConfig(rates = mapOf(RateKind.SOCIAL to Rate(2, 60))))
          val ash = fx.player("Ash")
          fx.player("Gary")
          fx.req(ash, SocialOp.PROFILE, "Gary")
          fx.req(ash, SocialOp.PROFILE, "Gary")
          fx.req(ash, SocialOp.PROFILE, "Gary")
          ash.notices().last() shouldBe "Slow down a little."
        }
      }

      test("a profile shows party, guild, friendship and trades; a blocker stays hidden") {
        runTest {
          val fx = SocialFixture(backgroundScope)
          val ash = fx.player("Ash")
          val gary = fx.player("Gary", party = listOf(1, 4, 7))
          fx.store.addMoney(gary.id, 100_000)
          fx.req(gary, SocialOp.GUILD_CREATE, "Rivals", "RVL")
          fx.req(ash, SocialOp.FRIEND_ADD, "Gary")
          fx.repo.recordTrade(ash.id, gary.id, "a", "b", LocalDateTime.now())
          fx.req(ash, SocialOp.PROFILE, "gary")
          val p =
              ash.session.sent
                  .filterIsInstance<CommunityPacket>()
                  .map { it.body }
                  .filterIsInstance<CommunityBody.Profile>()
                  .last()
          p.name shouldBe "Gary"
          p.online shouldBe true
          p.party.map { it.dexId } shouldContainExactly listOf(1, 4, 7)
          p.guildName shouldBe "Rivals"
          p.guildTag shouldBe "RVL"
          p.friend shouldBe true
          p.trades shouldBe 1

          fx.req(gary, SocialOp.BLOCK, "Ash")
          fx.req(ash, SocialOp.PROFILE, "Gary")
          val hidden =
              ash.session.sent
                  .filterIsInstance<CommunityPacket>()
                  .map { it.body }
                  .filterIsInstance<CommunityBody.Profile>()
                  .last()
          hidden.online shouldBe false
          hidden.location.known shouldBe false
        }
      }

      test("trade history is sent newest first from the asker's side") {
        runTest {
          val fx = SocialFixture(backgroundScope)
          val ash = fx.player("Ash")
          val gary = fx.player("Gary")
          fx.repo.recordTrade(ash.id, gary.id, "PIKACHU Lv5", "500 money", LocalDateTime.now())
          fx.repo.recordTrade(gary.id, ash.id, "2x Potion", "nothing", LocalDateTime.now())
          fx.req(ash, SocialOp.TRADE_HISTORY)
          val h =
              ash.session.sent
                  .filterIsInstance<CommunityPacket>()
                  .map { it.body }
                  .filterIsInstance<CommunityBody.TradeHistory>()
                  .single()
          h.entries.map { it.gave to it.got } shouldContainExactly
              listOf("nothing" to "2x Potion", "PIKACHU Lv5" to "500 money")
          h.entries.first().partner shouldBe "Gary"
        }
      }

      test("guild: found, invite, accept, ranks, motd, kick, leave, disband") {
        runTest {
          val fx = SocialFixture(backgroundScope, SocialConfig(guildFoundCost = 1000))
          val ash = fx.player("Ash")
          val gary = fx.player("Gary")
          val misty = fx.player("Misty")
          fx.req(ash, SocialOp.GUILD_CREATE, "x", "PAL")
          ash.notices().last() shouldContain "3-24"
          fx.req(ash, SocialOp.GUILD_CREATE, "Pallet Pals", "P")
          ash.notices().last() shouldContain "2-5"
          fx.req(ash, SocialOp.GUILD_CREATE, "Pallet Pals", "PAL")
          fx.store.getCharacter(ash.id)!!.info.money shouldBe 29000
          ash.guild().name shouldBe "Pallet Pals"
          ash.guild().myRank shouldBe GuildRank.BOSS.ordinal
          fx.req(gary, SocialOp.GUILD_CREATE, "pallet pals", "PP")
          gary.notices().last() shouldBe "A guild named pallet pals already exists."

          fx.req(ash, SocialOp.GUILD_INVITE, "Gary")
          gary.session.sent
              .filterIsInstance<CommunityPacket>()
              .map { it.body }
              .filterIsInstance<CommunityBody.GuildInvite>()
              .last()
              .guildName shouldBe "Pallet Pals"
          fx.req(gary, SocialOp.GUILD_ANSWER, arg = 1)
          gary.guild().myRank shouldBe GuildRank.GRUNT.ordinal
          ash.guild().members.map { it.name } shouldContainExactly listOf("Ash", "Gary")

          // A recruit cannot invite or set the message.
          fx.req(gary, SocialOp.GUILD_INVITE, "Misty")
          gary.notices().last() shouldBe "Only officers and above can invite."
          fx.req(gary, SocialOp.GUILD_MOTD, text = "hi")
          gary.notices().last() shouldBe "Only officers and above can set the message."

          fx.req(ash, SocialOp.GUILD_RANK, "Gary", arg = GuildRank.OFFICER.ordinal)
          gary.guild().myRank shouldBe GuildRank.OFFICER.ordinal
          fx.req(gary, SocialOp.GUILD_MOTD, text = "Meet at the PC")
          ash.guild().motd shouldBe "Meet at the PC"
          // An officer cannot promote someone to their own rank.
          fx.req(gary, SocialOp.GUILD_INVITE, "Misty")
          fx.req(misty, SocialOp.GUILD_ANSWER, arg = 1)
          fx.req(gary, SocialOp.GUILD_RANK, "Misty", arg = GuildRank.OFFICER.ordinal)
          gary.notices().last() shouldBe "You cannot make Misty Officer."
          fx.req(gary, SocialOp.GUILD_KICK, "Ash")
          gary.notices().last() shouldBe "You cannot kick a Leader."
          fx.req(gary, SocialOp.GUILD_KICK, "Misty")
          misty.guild().inGuild shouldBe false
          misty.notices() shouldContain "You were removed from Pallet Pals."

          fx.req(ash, SocialOp.GUILD_LEAVE)
          ash.notices().last() shouldBe "Hand the leadership to someone first, or disband."
          fx.req(ash, SocialOp.GUILD_RANK, "Gary", arg = GuildRank.BOSS.ordinal)
          gary.guild().myRank shouldBe GuildRank.BOSS.ordinal
          ash.guild().myRank shouldBe GuildRank.EXECUTIVE.ordinal
          fx.req(ash, SocialOp.GUILD_LEAVE)
          ash.guild().inGuild shouldBe false
          gary.guild().members.map { it.name } shouldContainExactly listOf("Gary")
          gary.guild().log.first().text shouldBe "Ash left."

          fx.req(gary, SocialOp.GUILD_DISBAND)
          gary.guild().inGuild shouldBe false
          fx.repo.guildOf(gary.id) shouldBe null
        }
      }

      test("guild invites expire after a minute and can be declined") {
        runTest {
          val fx = SocialFixture(backgroundScope, SocialConfig(guildFoundCost = 0))
          val ash = fx.player("Ash")
          val gary = fx.player("Gary")
          fx.req(ash, SocialOp.GUILD_CREATE, "Pallet Pals", "PAL")
          fx.req(ash, SocialOp.GUILD_INVITE, "Gary")
          advanceTimeBy(61_000)
          gary.notices().last() shouldBe "The invite to Pallet Pals expired."
          fx.req(gary, SocialOp.GUILD_ANSWER, arg = 1)
          gary.notices().last() shouldBe "You have no guild invite."
          fx.req(ash, SocialOp.GUILD_INVITE, "Gary")
          fx.req(gary, SocialOp.GUILD_ANSWER, arg = 0)
          ash.notices().last() shouldBe "Gary declined the invite."
          fx.repo.guildOf(gary.id) shouldBe null
        }
      }

      test("someone who blocked you cannot be invited") {
        runTest {
          val fx = SocialFixture(backgroundScope, SocialConfig(guildFoundCost = 0))
          val ash = fx.player("Ash")
          val gary = fx.player("Gary")
          fx.req(ash, SocialOp.GUILD_CREATE, "Pallet Pals", "PAL")
          fx.req(gary, SocialOp.BLOCK, "Ash")
          fx.req(ash, SocialOp.GUILD_INVITE, "Gary")
          ash.notices().last() shouldBe "Gary cannot be invited."
        }
      }

      test("the last member leaving dissolves the guild; founding needs the money") {
        runTest {
          val fx = SocialFixture(backgroundScope, SocialConfig(guildFoundCost = 50_000))
          val ash = fx.player("Ash")
          fx.req(ash, SocialOp.GUILD_CREATE, "Pallet Pals", "PAL")
          ash.notices().last() shouldBe "Founding a guild costs 50000."
          fx.store.addMoney(ash.id, 50_000)
          fx.req(ash, SocialOp.GUILD_CREATE, "Pallet Pals", "PAL")
          fx.store.getCharacter(ash.id)!!.info.money shouldBe 30000
          fx.req(ash, SocialOp.GUILD_LEAVE)
          ash.notices().last() shouldBe "You left Pallet Pals; it was dissolved."
          fx.repo.guildOf(ash.id) shouldBe null
        }
      }

      test("guild rules") {
        GuildRules.canKick(GuildRank.OFFICER, GuildRank.MEMBER) shouldBe true
        GuildRules.canKick(GuildRank.OFFICER, GuildRank.OFFICER) shouldBe false
        GuildRules.canKick(GuildRank.MEMBER, GuildRank.GRUNT) shouldBe false
        GuildRules.canSetRank(GuildRank.EXECUTIVE, GuildRank.MEMBER, GuildRank.COMMANDER) shouldBe
            true
        GuildRules.canSetRank(GuildRank.EXECUTIVE, GuildRank.MEMBER, GuildRank.EXECUTIVE) shouldBe
            false
        GuildRules.canSetRank(GuildRank.EXECUTIVE, GuildRank.MEMBER, GuildRank.BOSS) shouldBe false
        GuildRules.canSetRank(GuildRank.BOSS, GuildRank.MEMBER, GuildRank.BOSS) shouldBe true
        GuildRules.canInvite(GuildRank.MEMBER) shouldBe false
        GuildRules.label(GuildRank.GRUNT) shouldBe "Recruit"
      }

      test("legacy chat packet types map onto channels") {
        runTest {
          val fx = SocialFixture(backgroundScope)
          val ash = fx.player("Ash", mapId = 3)
          val misty = fx.player("Misty", mapId = 9)
          fx.social.onLegacyChat(
              PacketEvent(
                  ChatMessagePacket(
                      de.fiereu.openmmo.common.enums.ChatType.NORMAL,
                      de.fiereu.openmmo.common.enums.Language.EN,
                      "local hi",
                      ""),
                  ash.session))
          misty.chat().shouldBeEmpty()
          ash.chat().single().channel shouldBe ChatChannel.LOCAL
          fx.social.onLegacyChat(
              PacketEvent(
                  ChatMessagePacket(
                      de.fiereu.openmmo.common.enums.ChatType.GLOBAL,
                      de.fiereu.openmmo.common.enums.Language.EN,
                      "global hi",
                      ""),
                  ash.session))
          misty.chat().single().channel.shouldNotBeNull() shouldBe ChatChannel.GLOBAL
        }
      }
    })
