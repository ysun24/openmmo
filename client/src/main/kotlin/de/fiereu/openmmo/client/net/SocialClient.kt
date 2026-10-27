package de.fiereu.openmmo.client.net

import de.fiereu.openmmo.net.game.packets.social.ChatChannel
import de.fiereu.openmmo.net.game.packets.social.CommunityBody
import de.fiereu.openmmo.net.game.packets.social.SocialOp
import de.fiereu.openmmo.net.game.packets.social.SocialRequestPacket
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * Friends, blocks, profiles, guild and trade history (r3-social). Requests go out as
 * [SocialRequestPacket]; the server answers with [CommunityBody] messages fed in by [GameClient].
 * Chat lines are not kept here: they go to the world's chat log with their channel.
 */
class SocialClient(private val send: (Any) -> Unit, private val onChange: () -> Unit = {}) {
  data class State(
      val friends: CommunityBody.Friends = CommunityBody.Friends(emptyList(), emptyList()),
      val profile: CommunityBody.Profile? = null,
      /** Bumped on every profile reply, so a UI can tell a repeated one apart. */
      val profileSeq: Int = 0,
      val guild: CommunityBody.Guild = CommunityBody.Guild(inGuild = false),
      val invite: Invite? = null,
      val tradeHistory: List<de.fiereu.openmmo.net.game.packets.social.TradeRecord>? = null,
  )

  /** A pending guild invite and when (epoch ms) it lapses. */
  data class Invite(
      val from: String,
      val guildName: String,
      val guildTag: String,
      val expiresAtMs: Long,
  )

  @Volatile
  var state = State()
    private set

  fun request(op: SocialOp, name: String = "", text: String = "", arg: Int = 0) =
      send(SocialRequestPacket(op, name, text, arg))

  fun refresh() = request(SocialOp.REFRESH)

  fun addFriend(name: String) = request(SocialOp.FRIEND_ADD, name)

  fun removeFriend(name: String) = request(SocialOp.FRIEND_REMOVE, name)

  fun block(name: String) = request(SocialOp.BLOCK, name)

  fun unblock(name: String) = request(SocialOp.UNBLOCK, name)

  fun profile(name: String) = request(SocialOp.PROFILE, name)

  fun chat(channel: ChatChannel, text: String, to: String = "") =
      request(SocialOp.CHAT, to, text, channel.ordinal)

  fun createGuild(name: String, tag: String) = request(SocialOp.GUILD_CREATE, name, tag)

  fun inviteToGuild(name: String) = request(SocialOp.GUILD_INVITE, name)

  fun answerInvite(accept: Boolean) = request(SocialOp.GUILD_ANSWER, arg = if (accept) 1 else 0)

  fun leaveGuild() = request(SocialOp.GUILD_LEAVE)

  fun kick(name: String) = request(SocialOp.GUILD_KICK, name)

  fun setRank(name: String, rank: Int) = request(SocialOp.GUILD_RANK, name, arg = rank)

  fun setMotd(text: String) = request(SocialOp.GUILD_MOTD, text = text)

  fun disbandGuild() = request(SocialOp.GUILD_DISBAND)

  fun requestTradeHistory() = request(SocialOp.TRADE_HISTORY)

  /** True when [body] was a social message; market extras are handled by [MarketClient]. */
  fun onBody(body: CommunityBody): Boolean {
    state =
        when (body) {
          is CommunityBody.Friends -> state.copy(friends = body)
          is CommunityBody.Profile -> state.copy(profile = body, profileSeq = state.profileSeq + 1)
          is CommunityBody.Guild -> state.copy(guild = body)
          is CommunityBody.GuildInvite ->
              state.copy(
                  invite =
                      if (body.guildName.isEmpty()) null
                      else
                          Invite(
                              body.from,
                              body.guildName,
                              body.guildTag,
                              System.currentTimeMillis() + body.expiresInSeconds * 1000L))
          is CommunityBody.TradeHistory -> state.copy(tradeHistory = body.entries)
          else -> return false
        }
    log.debug { "social: ${body::class.simpleName}" }
    onChange()
    return true
  }
}
