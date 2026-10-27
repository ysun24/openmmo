package de.fiereu.openmmo.server.game.services

import de.fiereu.network.PacketEvent
import de.fiereu.network.SessionAttribute
import de.fiereu.network.SessionContext
import de.fiereu.openmmo.common.enums.ChatType
import de.fiereu.openmmo.net.game.packets.AddFriendPacket
import de.fiereu.openmmo.net.game.packets.BlockPlayerPacket
import de.fiereu.openmmo.net.game.packets.CancelSocialInteractionPacket
import de.fiereu.openmmo.net.game.packets.ChatMessagePacket
import de.fiereu.openmmo.net.game.packets.RemoveFriendPacket
import de.fiereu.openmmo.net.game.packets.RequestSocialProfilePacket
import de.fiereu.openmmo.net.game.packets.UnblockPlayerPacket
import de.fiereu.openmmo.net.game.packets.social.ChatChannel
import de.fiereu.openmmo.net.game.packets.social.CommunityBody
import de.fiereu.openmmo.net.game.packets.social.CommunityPacket
import de.fiereu.openmmo.net.game.packets.social.FriendView
import de.fiereu.openmmo.net.game.packets.social.ProfileMon
import de.fiereu.openmmo.net.game.packets.social.SocialOp
import de.fiereu.openmmo.net.game.packets.social.SocialRequestPacket
import de.fiereu.openmmo.net.game.packets.social.TradeRecord
import de.fiereu.openmmo.server.game.services.command.ChatCommandService
import de.fiereu.openmmo.server.game.services.social.BlockList
import de.fiereu.openmmo.server.game.services.social.ChatFilter
import de.fiereu.openmmo.server.game.services.social.OnlineDirectory
import de.fiereu.openmmo.server.game.services.social.RateKind
import de.fiereu.openmmo.server.game.services.social.RateLimiter
import de.fiereu.openmmo.server.game.services.social.SocialConfig
import de.fiereu.openmmo.server.game.services.social.cleanText
import de.fiereu.openmmo.server.game.session.PLAYER_STATE
import de.fiereu.openmmo.server.game.storage.CharRef
import de.fiereu.openmmo.server.game.storage.CharacterRepository
import de.fiereu.openmmo.server.game.storage.SocialRepository
import de.fiereu.openmmo.server.game.storage.StoredCharacter
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val log = KotlinLogging.logger {}

/** The character whose join this session already announced. */
private val SOCIAL_JOINED = SessionAttribute.of<Long>("socialJoined")

private const val MAX_FRIENDS = 100
private const val TRADE_HISTORY_ROWS = 30

/**
 * Friends, blocks, profiles and every player chat channel. Guild actions are forwarded to
 * [GuildService]. All requests come in as [SocialRequestPacket] (c2s 0xFC); answers go out as
 * [CommunityPacket] (s2c 0xFD), refusals as a notice.
 *
 * Chat: [ChatChannel.LOCAL] reaches the sender's map, GLOBAL and TRADE every online player, GUILD
 * the online guild, WHISPER one player. A line is cleaned, length checked, rate limited per
 * account, passed through the [ChatFilter] hook and never delivered to someone who blocked the
 * sender.
 */
@Singleton
class SocialService
@Inject
constructor(
    private val repo: SocialRepository,
    private val directory: OnlineDirectory,
    private val characters: CharacterRepository,
    private val blocks: BlockList,
    private val guilds: GuildService,
    private val config: SocialConfig,
    private val limiter: RateLimiter,
    private val filter: ChatFilter,
    private val scope: CoroutineScope,
    /**
     * Slash lines that reach a chat channel (a client that sends commands on the social packet, or
     * the retail chat packet) run as commands, so a command and its reply never reach anyone but
     * the caller. Lazy: commands may themselves depend on social services.
     */
    private val commands: Provider<ChatCommandService>? = null,
) {
  private fun now(): LocalDateTime = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS)

  private fun epochNow(): Long = System.currentTimeMillis() / 1000

  /**
   * Called after every map arrival. The first one of a session sends the friend list and guild and
   * tells friends the player is online; later ones (warps) do nothing.
   */
  fun sendFriendList(ctx: SessionContext) {
    val me = ctx.attributes[PLAYER_STATE]?.characterId ?: return
    if (ctx.attributes[SOCIAL_JOINED] == me) return
    ctx.attributes[SOCIAL_JOINED] = me
    scope.launch {
      runCatching { onJoin(ctx, me) }.onFailure { log.warn(it) { "social join of $me failed" } }
    }
  }

  suspend fun onJoin(ctx: SessionContext, me: Long) {
    ctx.send(CommunityPacket(friendsBody(me)))
    guilds.onJoin(me)
    announcePresence(me, online = true)
  }

  /** The player left: tell friends and the guild. Runs after the call returns. */
  fun onDisconnect(me: Long, userId: Int) {
    scope.launch {
      runCatching {
            guilds.onLeave(me)
            announcePresence(me, online = false)
          }
          .onFailure { log.warn(it) { "social leave of $me failed" } }
      blocks.invalidate(me)
    }
  }

  private suspend fun announcePresence(me: Long, online: Boolean) {
    val name = directory.nameOf(me) ?: repo.findById(me)?.name ?: return
    for (id in repo.friendedBy(me)) {
      val session = directory.session(id) ?: continue
      if (blocks.isBlocked(id, me)) continue
      session.send(notice(if (online) "$name is online." else "$name went offline."))
      session.send(CommunityPacket(friendsBody(id, overrideOffline = me.takeIf { !online })))
    }
  }

  suspend fun onRequest(event: PacketEvent<SocialRequestPacket>) {
    val session = event.session
    val state = session.attributes[PLAYER_STATE] ?: return
    val me = state.characterId ?: return
    val p = event.packet
    if (p.op != SocialOp.CHAT && !limiter.tryAcquire(state.userId, RateKind.SOCIAL)) {
      return tell(session, "Slow down a little.")
    }
    when (p.op) {
      SocialOp.REFRESH -> {
        session.send(CommunityPacket(friendsBody(me)))
        guilds.sendState(me)
      }
      SocialOp.FRIEND_ADD -> addFriend(session, me, p.name)
      SocialOp.FRIEND_REMOVE -> removeFriend(session, me, p.name)
      SocialOp.BLOCK -> block(session, me, p.name)
      SocialOp.UNBLOCK -> unblock(session, me, p.name)
      SocialOp.PROFILE -> profile(session, me, p.name)
      SocialOp.CHAT -> {
        val channel = ChatChannel.entries.getOrNull(p.arg) ?: ChatChannel.LOCAL
        chat(session, state.userId, me, channel, p.name, p.text)
      }
      SocialOp.GUILD_CREATE -> guilds.create(session, me, p.name, p.text)
      SocialOp.GUILD_INVITE -> guilds.invite(session, me, p.name)
      SocialOp.GUILD_ANSWER -> guilds.answer(session, me, p.arg != 0)
      SocialOp.GUILD_LEAVE -> guilds.leave(session, me)
      SocialOp.GUILD_KICK -> guilds.kick(session, me, p.name)
      SocialOp.GUILD_RANK -> guilds.setRank(session, me, p.name, p.arg)
      SocialOp.GUILD_MOTD -> guilds.setMotd(session, me, p.text)
      SocialOp.GUILD_DISBAND -> guilds.disband(session, me)
      SocialOp.TRADE_HISTORY -> tradeHistory(session, me)
      SocialOp.FRIEND_ACCEPT -> answerRequest(session, me, p.name, accept = true)
      SocialOp.FRIEND_DECLINE -> answerRequest(session, me, p.name, accept = false)
    }
  }

  /**
   * A friend request is a one-way row the other player wrote; accepting writes ours (mutual),
   * declining removes theirs.
   */
  private suspend fun answerRequest(
      session: SessionContext,
      me: Long,
      name: String,
      accept: Boolean
  ) {
    val from = resolve(session, name) ?: return
    if (from.id !in repo.friendedBy(me) || repo.friends(me).any { it.friend.id == from.id }) {
      return tell(session, "${from.name} has not asked to be your friend.")
    }
    val myName = directory.nameOf(me) ?: "Someone"
    if (accept) {
      repo.addFriend(me, from.id, now())
      log.info { "char=$me accepted the friend request of char=${from.id}" }
      tell(session, "You and ${from.name} are now friends.")
      directory.session(from.id)?.send(notice("$myName accepted your friend request."))
    } else {
      repo.removeFriend(from.id, me)
      tell(session, "You declined ${from.name}'s friend request.")
    }
    session.send(CommunityPacket(friendsBody(me)))
    directory.session(from.id)?.send(CommunityPacket(friendsBody(from.id)))
  }

  // ------------------------------------------------------------------ friends and blocks

  private suspend fun resolve(session: SessionContext, name: String): CharRef? {
    val trimmed = name.trim()
    if (trimmed.isEmpty() || trimmed.length > 32) {
      tell(session, "Enter a trainer name.")
      return null
    }
    val found = repo.findByName(trimmed)
    if (found == null) tell(session, "There is no trainer named $trimmed.")
    return found
  }

  private suspend fun addFriend(session: SessionContext, me: Long, name: String) {
    val target = resolve(session, name) ?: return
    if (target.id == me) return tell(session, "You cannot add yourself.")
    if (blocks.isBlocked(me, target.id)) return tell(session, "Unblock ${target.name} first.")
    if (repo.friends(me).size >= MAX_FRIENDS) return tell(session, "Your friend list is full.")
    if (!repo.addFriend(me, target.id, now())) {
      return tell(session, "${target.name} already is your friend.")
    }
    log.info { "char=$me added friend char=${target.id}" }
    val mutual = target.id in repo.friendedBy(me)
    tell(
        session,
        if (mutual) "You and ${target.name} are now friends."
        else "Sent ${target.name} a friend request.")
    session.send(CommunityPacket(friendsBody(me)))
    val myName = directory.nameOf(me) ?: "Someone"
    directory.session(target.id)?.let { their ->
      if (!blocks.isBlocked(target.id, me)) {
        their.send(
            notice(
                if (mutual) "$myName accepted your friend request."
                else "$myName wants to be your friend. Answer in FRIENDS."))
        their.send(CommunityPacket(friendsBody(target.id)))
      }
    }
  }

  private suspend fun removeFriend(session: SessionContext, me: Long, name: String) {
    val target = resolve(session, name) ?: return
    if (!repo.removeFriend(me, target.id))
        return tell(session, "${target.name} is not your friend.")
    tell(session, "Removed ${target.name} from your friends.")
    session.send(CommunityPacket(friendsBody(me)))
    directory.session(target.id)?.send(CommunityPacket(friendsBody(target.id)))
  }

  private suspend fun block(session: SessionContext, me: Long, name: String) {
    val target = resolve(session, name) ?: return
    if (target.id == me) return tell(session, "You cannot block yourself.")
    if (!repo.block(me, target.id, now()))
        return tell(session, "${target.name} is already blocked.")
    // A blocked player does not stay a friend.
    repo.removeFriend(me, target.id)
    blocks.invalidate(me)
    log.info { "char=$me blocked char=${target.id}" }
    tell(session, "Blocked ${target.name}. You will not see their messages or requests.")
    session.send(CommunityPacket(friendsBody(me)))
  }

  private suspend fun unblock(session: SessionContext, me: Long, name: String) {
    val target = resolve(session, name) ?: return
    if (!repo.unblock(me, target.id)) return tell(session, "${target.name} is not blocked.")
    blocks.invalidate(me)
    tell(session, "Unblocked ${target.name}.")
    session.send(CommunityPacket(friendsBody(me)))
  }

  /** [overrideOffline] is shown offline even if its session is still being torn down. */
  suspend fun friendsBody(me: Long, overrideOffline: Long? = null): CommunityBody.Friends {
    val rows = repo.friends(me)
    val friends =
        rows.map { row ->
          val id = row.friend.id
          // Someone who blocked us does not reveal where they are; nor does a request that was
          // not accepted yet (only mutual friends see each other's location).
          val visible =
              id != overrideOffline &&
                  row.mutual &&
                  directory.isOnline(id) &&
                  !blocks.isBlocked(id, me)
          FriendView(
              row.friend.name,
              visible,
              if (visible) directory.location(id)
              else de.fiereu.openmmo.net.game.packets.social.MapRef.UNKNOWN,
              row.since.toEpochSecond(ZoneOffset.UTC),
              row.mutual)
        }
    val mine = rows.map { it.friend.id }.toSet()
    val blockedByMe = blocks.blockedBy(me)
    val requests =
        repo
            .friendedBy(me)
            .filter { it !in mine && it !in blockedByMe }
            .mapNotNull { repo.findById(it)?.name }
    return CommunityBody.Friends(friends, repo.blocked(me).map { it.name }, requests)
  }

  // ------------------------------------------------------------------ profile

  private suspend fun profile(session: SessionContext, me: Long, name: String) {
    val target = resolve(session, name) ?: return
    val online = directory.isOnline(target.id)
    val hidden = blocks.isBlocked(target.id, me)
    val stored: StoredCharacter? =
        if (online) directory.online().firstOrNull { it.info.id == target.id }
        else characters.loadById(target.id)
    val guild = guilds.guildOf(target.id)
    session.send(
        CommunityPacket(
            CommunityBody.Profile(
                name = target.name,
                online = online && !hidden,
                location =
                    if (online && !hidden) directory.location(target.id)
                    else de.fiereu.openmmo.net.game.packets.social.MapRef.UNKNOWN,
                guildName = guild?.name.orEmpty(),
                guildTag = guild?.tag.orEmpty(),
                party =
                    stored
                        ?.pokemon
                        ?.sortedBy { it.containerSlot }
                        ?.map { ProfileMon(it.dexId, it.level.toInt(), it.isShiny) }
                        .orEmpty(),
                createdEpoch = target.createdAt.toEpochSecond(ZoneOffset.UTC),
                friend = repo.friends(me).any { it.friend.id == target.id },
                blocked = blocks.isBlocked(me, target.id),
                trades = repo.tradeCount(target.id),
            )))
  }

  private suspend fun tradeHistory(session: SessionContext, me: Long) {
    val rows = repo.tradeHistory(me, TRADE_HISTORY_ROWS)
    session.send(
        CommunityPacket(
            CommunityBody.TradeHistory(
                rows.map {
                  TradeRecord(it.at.toEpochSecond(ZoneOffset.UTC), it.partner, it.gave, it.got)
                })))
  }

  // ------------------------------------------------------------------ chat

  /** The retail chat packet: its type picks the channel, it cannot whisper. */
  suspend fun onLegacyChat(event: PacketEvent<ChatMessagePacket>) {
    val state = event.session.attributes[PLAYER_STATE] ?: return
    val me = state.characterId ?: return
    val channel =
        when (event.packet.type) {
          ChatType.GLOBAL,
          ChatType.SHOUT -> ChatChannel.GLOBAL
          ChatType.TRADE -> ChatChannel.TRADE
          ChatType.TEAM -> ChatChannel.GUILD
          else -> ChatChannel.LOCAL
        }
    chat(event.session, state.userId, me, channel, "", event.packet.message)
  }

  suspend fun chat(
      session: SessionContext,
      userId: Int,
      me: Long,
      channel: ChatChannel,
      to: String,
      raw: String,
  ) {
    val sender = directory.online().firstOrNull { it.info.id == me } ?: return
    if (channel == ChatChannel.SYSTEM) return
    if (raw.trimStart().startsWith("/")) {
      val router = commands?.get() ?: return system(session, "Commands are not available here.")
      router.tryHandle(session, raw)
      return
    }
    val cleaned = cleanText(raw) ?: return
    if (cleaned.length > config.chatMaxLength) {
      return system(session, "Messages are limited to ${config.chatMaxLength} characters.")
    }
    val kind = if (channel == ChatChannel.WHISPER) RateKind.WHISPER else RateKind.CHAT
    if (!limiter.tryAcquire(userId, kind)) {
      return system(session, "You are sending messages too fast.")
    }
    val text = filter.apply(cleaned) ?: return system(session, "That message was not sent.")
    val from = sender.info.name
    log.info { "Chat [$channel] $from${if (to.isNotEmpty()) " -> $to" else ""}: $text" }
    val recipients: List<Long> =
        when (channel) {
          ChatChannel.LOCAL -> {
            val here = directory.location(me)
            directory.online().map { it.info.id }.filter { directory.location(it) == here }
          }
          ChatChannel.GLOBAL,
          ChatChannel.TRADE -> directory.online().map { it.info.id }
          ChatChannel.GUILD -> {
            val guildId = guilds.guildIdOf(me) ?: return system(session, "You are not in a guild.")
            guilds.onlineMembers(guildId).ifEmpty { listOf(me) }
          }
          ChatChannel.WHISPER -> {
            val target =
                directory.byName(to) ?: return system(session, "${to.trim()} is not online.")
            if (target.info.id == me) return system(session, "You cannot whisper to yourself.")
            if (blocks.isBlocked(target.info.id, me)) {
              return system(session, "${target.info.name} is not accepting messages.")
            }
            listOf(me, target.info.id)
          }
          ChatChannel.SYSTEM -> return
        }
    val target = if (channel == ChatChannel.WHISPER) directory.byName(to)?.info?.name ?: to else ""
    val line = CommunityPacket(CommunityBody.Chat(channel, from, target, text, epochNow()))
    for (id in recipients.distinct()) {
      if (id != me && blocks.isBlocked(id, me)) continue
      directory.session(id)?.send(line)
    }
  }

  private fun system(session: SessionContext, text: String) {
    session.send(CommunityPacket(CommunityBody.Chat(ChatChannel.SYSTEM, "", "", text, epochNow())))
  }

  private fun tell(session: SessionContext, text: String) {
    session.send(notice(text))
  }

  // ------------------------------------------------------------------ retail packets

  suspend fun onAddFriend(event: PacketEvent<AddFriendPacket>) {
    val me = event.session.attributes[PLAYER_STATE]?.characterId ?: return
    addFriend(event.session, me, event.packet.username)
  }

  suspend fun onRemoveFriend(event: PacketEvent<RemoveFriendPacket>) {
    val me = event.session.attributes[PLAYER_STATE]?.characterId ?: return
    removeFriend(event.session, me, event.packet.username)
  }

  suspend fun onBlockPlayer(event: PacketEvent<BlockPlayerPacket>) {
    val me = event.session.attributes[PLAYER_STATE]?.characterId ?: return
    block(event.session, me, event.packet.username)
  }

  suspend fun onUnblockPlayer(event: PacketEvent<UnblockPlayerPacket>) {
    val me = event.session.attributes[PLAYER_STATE]?.characterId ?: return
    unblock(event.session, me, event.packet.username)
  }

  fun onRequestSocialProfile(event: PacketEvent<RequestSocialProfilePacket>) {
    log.info { "RequestSocialProfile targetId=${event.packet.targetId}" }
  }

  fun onCancelSocialInteraction(event: PacketEvent<CancelSocialInteractionPacket>) {
    log.info { "CancelSocialInteraction from ${event.session.remoteAddress}" }
  }
}
