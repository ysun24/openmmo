package de.fiereu.openmmo.server.game.services

import de.fiereu.network.PacketEvent
import de.fiereu.network.SessionContext
import de.fiereu.openmmo.common.enums.GuildRank
import de.fiereu.openmmo.net.game.packets.guild.GuildActivityLogPageRequestPacket
import de.fiereu.openmmo.net.game.packets.guild.GuildCreatePacket
import de.fiereu.openmmo.net.game.packets.guild.GuildDisbandPacket
import de.fiereu.openmmo.net.game.packets.guild.GuildInvitePacket
import de.fiereu.openmmo.net.game.packets.guild.GuildLeavePacket
import de.fiereu.openmmo.net.game.packets.guild.GuildMemberKickPacket
import de.fiereu.openmmo.net.game.packets.guild.GuildMemberRankAssignPacket
import de.fiereu.openmmo.net.game.packets.guild.GuildMotdUpdatePacket
import de.fiereu.openmmo.net.game.packets.guild.GuildRankLabelUpdatePacket
import de.fiereu.openmmo.net.game.packets.guild.GuildRankPermissionUpdatePacket
import de.fiereu.openmmo.net.game.packets.social.CommunityBody
import de.fiereu.openmmo.net.game.packets.social.CommunityPacket
import de.fiereu.openmmo.net.game.packets.social.GuildLogView
import de.fiereu.openmmo.net.game.packets.social.GuildMemberView
import de.fiereu.openmmo.server.game.services.social.BlockList
import de.fiereu.openmmo.server.game.services.social.ChatFilter
import de.fiereu.openmmo.server.game.services.social.OnlineDirectory
import de.fiereu.openmmo.server.game.services.social.SocialConfig
import de.fiereu.openmmo.server.game.services.social.cleanText
import de.fiereu.openmmo.server.game.session.PLAYER_STATE
import de.fiereu.openmmo.server.game.storage.CharacterStore
import de.fiereu.openmmo.server.game.storage.GuildRow
import de.fiereu.openmmo.server.game.storage.SocialRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val log = KotlinLogging.logger {}

/** How long a guild invite stays open. */
const val GUILD_INVITE_SECONDS = 60

private const val LOG_ENTRIES = 30
private val NAME_RULE = Regex("^[A-Za-z0-9][A-Za-z0-9 ]{1,22}[A-Za-z0-9]$")
private val TAG_RULE = Regex("^[A-Za-z0-9]{2,5}$")

/** Who may do what, by rank. GRUNT (recruit) < MEMBER < OFFICER < COMMANDER < EXECUTIVE < BOSS. */
object GuildRules {
  val LEADER = GuildRank.BOSS
  val NEW_MEMBER = GuildRank.GRUNT

  fun canInvite(rank: GuildRank) = rank >= GuildRank.OFFICER

  fun canSetMotd(rank: GuildRank) = rank >= GuildRank.OFFICER

  /** Kick only people below you. */
  fun canKick(actor: GuildRank, target: GuildRank) = actor >= GuildRank.OFFICER && target < actor

  /**
   * Change a lower member's rank to anything still below yours. Only the leader hands over BOSS,
   * which is a transfer, not a promotion.
   */
  fun canSetRank(actor: GuildRank, target: GuildRank, to: GuildRank) =
      if (to == GuildRank.BOSS) actor == GuildRank.BOSS
      else actor >= GuildRank.OFFICER && target < actor && to < actor

  fun label(rank: GuildRank): String =
      when (rank) {
        GuildRank.GRUNT -> "Recruit"
        GuildRank.MEMBER -> "Member"
        GuildRank.OFFICER -> "Officer"
        GuildRank.COMMANDER -> "Commander"
        GuildRank.EXECUTIVE -> "Executive"
        GuildRank.BOSS -> "Leader"
      }
}

/**
 * Guilds: found one, invite (the invitee accepts within [GUILD_INVITE_SECONDS]), ranks, kick,
 * leave, the message of the day, disband, and a member list with who is online where. Everything is
 * in the database (V8_4); the only in-memory state is pending invites. One mutex serialises every
 * change, so two officers cannot kick each other at once into an inconsistent guild.
 */
@Singleton
class GuildService
@Inject
constructor(
    private val repo: SocialRepository,
    private val directory: OnlineDirectory,
    private val characterStore: CharacterStore,
    private val config: SocialConfig,
    private val blocks: BlockList,
    private val filter: ChatFilter,
    private val scope: CoroutineScope,
) {
  private data class Invite(
      val guildId: Long,
      val guildName: String,
      val tag: String,
      val fromName: String,
      val expiresAtMs: Long,
  )

  private val mutex = Mutex()
  private val invites = ConcurrentHashMap<Long, Invite>()
  private val membership = ConcurrentHashMap<Long, Long>()

  private fun now(): LocalDateTime = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS)

  /** The guild id of an online member, cached from login on. */
  suspend fun guildIdOf(charId: Long): Long? =
      membership[charId] ?: repo.guildOf(charId)?.id?.also { membership[charId] = it }

  suspend fun guildOf(charId: Long): GuildRow? = repo.guildOf(charId)

  /** Online members of [guildId]. */
  fun onlineMembers(guildId: Long): List<Long> =
      directory.online().map { it.info.id }.filter { membership[it] == guildId }

  // ------------------------------------------------------------------ actions

  suspend fun create(session: SessionContext, me: Long, rawName: String, rawTag: String) =
      mutex.withLock {
        val name = cleanText(rawName) ?: return@withLock tell(session, "Give the guild a name.")
        val tag = rawTag.trim()
        if (!NAME_RULE.matches(name)) {
          return@withLock tell(session, "Guild names are 3-24 letters, digits or spaces.")
        }
        if (!TAG_RULE.matches(tag)) return@withLock tell(session, "Tags are 2-5 letters or digits.")
        if (filter.apply(name) != name || filter.apply(tag) != tag) {
          return@withLock tell(session, "That name is not allowed.")
        }
        if (repo.guildOf(me) != null) return@withLock tell(session, "You already are in a guild.")
        val stored = characterStore.getCharacter(me) ?: return@withLock
        val cost = config.guildFoundCost
        if (stored.info.money < cost) {
          return@withLock tell(session, "Founding a guild costs $cost.")
        }
        val guild =
            repo.createGuild(name, tag, me, GuildRules.LEADER.ordinal, now())
                ?: return@withLock tell(session, "A guild named $name already exists.")
        if (cost > 0 && !characterStore.addMoney(me, -cost)) {
          repo.disband(guild.id)
          return@withLock tell(session, "The guild could not be founded.")
        }
        membership[me] = guild.id
        repo.addGuildLog(guild.id, "${stored.info.name} founded the guild.", now())
        log.info { "char=$me founded guild ${guild.id} '$name' [$tag]" }
        tell(session, "You founded $name [$tag].")
        sendMoney(session, me)
        broadcast(guild.id)
      }

  suspend fun invite(session: SessionContext, me: Long, targetName: String) =
      mutex.withLock {
        val (guild, myRank) = myGuild(session, me) ?: return@withLock
        if (!GuildRules.canInvite(myRank)) {
          return@withLock tell(session, "Only officers and above can invite.")
        }
        val target =
            directory.byName(targetName)
                ?: return@withLock tell(session, "${targetName.trim()} is not online.")
        val targetId = target.info.id
        if (targetId == me) return@withLock tell(session, "You are already in the guild.")
        if (repo.guildOf(targetId) != null) {
          return@withLock tell(session, "${target.info.name} already is in a guild.")
        }
        if (blocks.eitherBlocked(me, targetId)) {
          return@withLock tell(session, "${target.info.name} cannot be invited.")
        }
        if (repo.guildMembers(guild.id).size >= config.guildMaxMembers) {
          return@withLock tell(session, "The guild is full.")
        }
        val inviterName = directory.nameOf(me) ?: "?"
        val invite =
            Invite(
                guild.id,
                guild.name,
                guild.tag,
                inviterName,
                System.currentTimeMillis() + GUILD_INVITE_SECONDS * 1000L)
        invites[targetId] = invite
        directory
            .session(targetId)
            ?.send(
                CommunityPacket(
                    CommunityBody.GuildInvite(
                        inviterName, guild.name, guild.tag, GUILD_INVITE_SECONDS)))
        directory.session(targetId)?.send(notice("$inviterName invited you to ${guild.name}."))
        tell(session, "Invited ${target.info.name} to ${guild.name}.")
        scope.launch {
          delay(GUILD_INVITE_SECONDS.seconds)
          if (invites.remove(targetId, invite)) {
            directory
                .session(targetId)
                ?.send(CommunityPacket(CommunityBody.GuildInvite(inviterName, "", "", 0)))
            directory.session(targetId)?.send(notice("The invite to ${guild.name} expired."))
          }
        }
      }

  suspend fun answer(session: SessionContext, me: Long, accept: Boolean) =
      mutex.withLock {
        val invite =
            invites.remove(me) ?: return@withLock tell(session, "You have no guild invite.")
        session.send(CommunityPacket(CommunityBody.GuildInvite(invite.fromName, "", "", 0)))
        if (System.currentTimeMillis() > invite.expiresAtMs) {
          return@withLock tell(session, "That invite expired.")
        }
        if (!accept) {
          directory.byName(invite.fromName)?.let {
            directory
                .session(it.info.id)
                ?.send(notice("${directory.nameOf(me)} declined the invite."))
          }
          return@withLock tell(session, "Declined the invite to ${invite.guildName}.")
        }
        val guild =
            repo.guildById(invite.guildId) ?: return@withLock tell(session, "That guild is gone.")
        if (repo.guildMembers(guild.id).size >= config.guildMaxMembers) {
          return@withLock tell(session, "The guild is full.")
        }
        if (!repo.addMember(guild.id, me, GuildRules.NEW_MEMBER.ordinal, now())) {
          return@withLock tell(session, "You already are in a guild.")
        }
        membership[me] = guild.id
        val myName = directory.nameOf(me) ?: "?"
        repo.addGuildLog(guild.id, "$myName joined (invited by ${invite.fromName}).", now())
        log.info { "char=$me joined guild ${guild.id}" }
        notifyMembers(guild.id, "$myName joined the guild.")
        broadcast(guild.id)
      }

  suspend fun leave(session: SessionContext, me: Long) =
      mutex.withLock {
        val (guild, myRank) = myGuild(session, me) ?: return@withLock
        val members = repo.guildMembers(guild.id)
        val myName = directory.nameOf(me) ?: "?"
        if (myRank == GuildRules.LEADER && members.size > 1) {
          return@withLock tell(session, "Hand the leadership to someone first, or disband.")
        }
        if (members.size <= 1) {
          repo.disband(guild.id)
          membership.remove(me)
          log.info { "char=$me left and dissolved guild ${guild.id}" }
          tell(session, "You left ${guild.name}; it was dissolved.")
          sendState(me)
          return@withLock
        }
        repo.removeMember(guild.id, me)
        membership.remove(me)
        repo.addGuildLog(guild.id, "$myName left.", now())
        tell(session, "You left ${guild.name}.")
        sendState(me)
        notifyMembers(guild.id, "$myName left the guild.")
        broadcast(guild.id)
      }

  suspend fun kick(session: SessionContext, me: Long, targetName: String) =
      mutex.withLock {
        val (guild, myRank) = myGuild(session, me) ?: return@withLock
        val target =
            repo.guildMembers(guild.id).firstOrNull {
              it.member.name.equals(targetName.trim(), ignoreCase = true)
            } ?: return@withLock tell(session, "${targetName.trim()} is not in the guild.")
        if (target.member.id == me) return@withLock tell(session, "Use Leave instead.")
        val targetRank = GuildRank.entries[target.rank]
        if (!GuildRules.canKick(myRank, targetRank)) {
          return@withLock tell(session, "You cannot kick a ${GuildRules.label(targetRank)}.")
        }
        repo.removeMember(guild.id, target.member.id)
        membership.remove(target.member.id)
        val myName = directory.nameOf(me) ?: "?"
        repo.addGuildLog(guild.id, "$myName removed ${target.member.name}.", now())
        directory.session(target.member.id)?.send(notice("You were removed from ${guild.name}."))
        sendState(target.member.id)
        notifyMembers(guild.id, "${target.member.name} was removed by $myName.")
        broadcast(guild.id)
      }

  suspend fun setRank(session: SessionContext, me: Long, targetName: String, rankOrdinal: Int) =
      mutex.withLock {
        val (guild, myRank) = myGuild(session, me) ?: return@withLock
        val to =
            GuildRank.entries.getOrNull(rankOrdinal)
                ?: return@withLock tell(session, "No such rank.")
        val target =
            repo.guildMembers(guild.id).firstOrNull {
              it.member.name.equals(targetName.trim(), ignoreCase = true)
            } ?: return@withLock tell(session, "${targetName.trim()} is not in the guild.")
        if (target.member.id == me)
            return@withLock tell(session, "You cannot change your own rank.")
        val targetRank = GuildRank.entries[target.rank]
        if (!GuildRules.canSetRank(myRank, targetRank, to)) {
          return@withLock tell(
              session, "You cannot make ${target.member.name} ${GuildRules.label(to)}.")
        }
        val myName = directory.nameOf(me) ?: "?"
        if (to == GuildRules.LEADER) {
          repo.transferLeadership(
              guild.id, me, target.member.id, GuildRank.BOSS.ordinal, GuildRank.EXECUTIVE.ordinal)
          repo.addGuildLog(
              guild.id, "$myName handed the leadership to ${target.member.name}.", now())
        } else {
          repo.setRank(guild.id, target.member.id, to.ordinal)
          val verb = if (to > targetRank) "promoted" else "demoted"
          repo.addGuildLog(
              guild.id, "$myName $verb ${target.member.name} to ${GuildRules.label(to)}.", now())
        }
        broadcast(guild.id)
      }

  suspend fun setMotd(session: SessionContext, me: Long, raw: String) =
      mutex.withLock {
        val (guild, myRank) = myGuild(session, me) ?: return@withLock
        if (!GuildRules.canSetMotd(myRank)) {
          return@withLock tell(session, "Only officers and above can set the message.")
        }
        val motd = (cleanText(raw) ?: "").take(config.chatMaxLength)
        val filtered =
            filter.apply(motd) ?: return@withLock tell(session, "That message is not allowed.")
        repo.setMotd(guild.id, filtered)
        repo.addGuildLog(guild.id, "${directory.nameOf(me)} changed the message of the day.", now())
        broadcast(guild.id)
      }

  suspend fun disband(session: SessionContext, me: Long) =
      mutex.withLock {
        val (guild, myRank) = myGuild(session, me) ?: return@withLock
        if (myRank != GuildRules.LEADER)
            return@withLock tell(session, "Only the leader can disband.")
        val members = repo.guildMembers(guild.id).map { it.member.id }
        repo.disband(guild.id)
        members.forEach { membership.remove(it) }
        log.info { "char=$me disbanded guild ${guild.id}" }
        for (id in members) {
          directory.session(id)?.send(notice("${guild.name} was disbanded."))
          sendState(id)
        }
      }

  // ------------------------------------------------------------------ presence

  suspend fun onJoin(me: Long) {
    membership.remove(me)
    val id = guildIdOf(me)
    sendState(me)
    if (id != null) broadcast(id, except = me)
  }

  suspend fun onLeave(me: Long) {
    invites.remove(me)
    val id = membership.remove(me) ?: return
    broadcast(id, except = me)
  }

  // ------------------------------------------------------------------ state

  /** Sends [charId] its guild window (or "not in a guild"). */
  suspend fun sendState(charId: Long) {
    val session = directory.session(charId) ?: return
    session.send(CommunityPacket(stateFor(charId)))
  }

  suspend fun stateFor(charId: Long): CommunityBody.Guild {
    val guild = repo.guildOf(charId) ?: return CommunityBody.Guild(inGuild = false)
    val members = repo.guildMembers(guild.id)
    val myRank = members.firstOrNull { it.member.id == charId }?.rank ?: 0
    return CommunityBody.Guild(
        inGuild = true,
        name = guild.name,
        tag = guild.tag,
        motd = guild.motd,
        myRank = myRank,
        members =
            members.map {
              GuildMemberView(
                  it.member.name,
                  it.rank,
                  directory.isOnline(it.member.id),
                  directory.location(it.member.id),
                  it.joinedAt.toEpochSecond(ZoneOffset.UTC))
            },
        log =
            repo.guildLog(guild.id, LOG_ENTRIES).map {
              GuildLogView(it.at.toEpochSecond(ZoneOffset.UTC), it.text)
            },
    )
  }

  private suspend fun broadcast(guildId: Long, except: Long? = null) {
    for (row in repo.guildMembers(guildId)) {
      val id = row.member.id
      if (id == except) continue
      if (directory.isOnline(id)) {
        membership[id] = guildId
        sendState(id)
      }
    }
  }

  private suspend fun notifyMembers(guildId: Long, text: String) {
    for (row in repo.guildMembers(guildId)) directory.session(row.member.id)?.send(notice(text))
  }

  private suspend fun myGuild(session: SessionContext, me: Long): Pair<GuildRow, GuildRank>? {
    val guild = repo.guildOf(me)
    if (guild == null) {
      tell(session, "You are not in a guild.")
      return null
    }
    val rank = repo.guildMembers(guild.id).firstOrNull { it.member.id == me }?.rank ?: 0
    return guild to GuildRank.entries[rank]
  }

  private fun tell(session: SessionContext, text: String) {
    session.send(notice(text))
  }

  private fun sendMoney(session: SessionContext, me: Long) {
    val money = characterStore.getCharacter(me)?.info?.money ?: return
    session.send(de.fiereu.openmmo.net.game.packets.CurrencyBalancePacket(money, 0))
    session.send(de.fiereu.openmmo.net.game.packets.LocalCharacterDeltaPacket(money = money))
  }

  // ------------------------------------------------------------------ retail packets
  // The retail guild window's packets map onto the same actions. Member ids are entity ids, which
  // for players are character ids.

  private fun PacketEvent<*>.me(): Long? = session.attributes[PLAYER_STATE]?.characterId

  suspend fun onCreateGuild(event: PacketEvent<GuildCreatePacket>) {
    val me = event.me() ?: return
    create(event.session, me, event.packet.guildName, event.packet.guildTag)
  }

  suspend fun onActivityLogPageRequest(event: PacketEvent<GuildActivityLogPageRequestPacket>) {
    event.me()?.let { sendState(it) }
  }

  suspend fun onGuildInvite(event: PacketEvent<GuildInvitePacket>) {
    val me = event.me() ?: return
    invite(event.session, me, event.packet.targetName)
  }

  suspend fun onRankAssign(event: PacketEvent<GuildMemberRankAssignPacket>) {
    val me = event.me() ?: return
    val name = repo.findById(event.packet.memberEntityId)?.name ?: return
    setRank(event.session, me, name, event.packet.rankOrdinal)
  }

  suspend fun onKick(event: PacketEvent<GuildMemberKickPacket>) {
    val me = event.me() ?: return
    val name = repo.findById(event.packet.targetEntityId)?.name ?: return
    kick(event.session, me, name)
  }

  suspend fun onLeave(event: PacketEvent<GuildLeavePacket>) {
    val me = event.me() ?: return
    leave(event.session, me)
  }

  suspend fun onDisband(event: PacketEvent<GuildDisbandPacket>) {
    val me = event.me() ?: return
    if (event.packet.initiate) disband(event.session, me)
  }

  suspend fun onMotdUpdate(event: PacketEvent<GuildMotdUpdatePacket>) {
    val me = event.me() ?: return
    setMotd(event.session, me, event.packet.motdText)
  }

  fun onRankLabelUpdate(event: PacketEvent<GuildRankLabelUpdatePacket>) {
    log.info { "Ignoring retail rank label update ${event.packet.rankOrdinal}" }
  }

  fun onRankPermissionUpdate(event: PacketEvent<GuildRankPermissionUpdatePacket>) {
    log.info { "Ignoring retail rank permission update; permissions are fixed by GuildRules" }
  }
}
