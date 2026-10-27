package de.fiereu.openmmo.server.game.storage

import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicLong

/**
 * [SocialRepository] in memory, for tests and for wiring services without a database. Characters
 * must be [register]ed to be found by name. Not thread safe: meant for single-threaded tests.
 */
class InMemorySocialRepository : SocialRepository {
  private val chars = LinkedHashMap<Long, CharRef>()
  private val friends = LinkedHashMap<Pair<Long, Long>, LocalDateTime>()
  private val blocks = LinkedHashMap<Pair<Long, Long>, LocalDateTime>()
  private val guilds = LinkedHashMap<Long, GuildRow>()

  private data class Member(val guildId: Long, val rank: Int, val joinedAt: LocalDateTime)

  private val members = LinkedHashMap<Long, Member>()
  private val logs = ArrayList<Pair<Long, GuildLogRow>>()

  private data class Trade(
      val at: LocalDateTime,
      val a: Long,
      val b: Long,
      val gaveA: String,
      val gaveB: String
  )

  private val trades = ArrayList<Trade>()
  private val ids = AtomicLong(1)

  @Synchronized
  fun register(
      id: Long,
      name: String,
      userId: Int = 0,
      createdAt: LocalDateTime = LocalDateTime.now()
  ) {
    chars[id] = CharRef(id, name, userId, createdAt)
  }

  override suspend fun findByName(name: String): CharRef? =
      chars.values
          .filter { it.name.equals(name.trim(), ignoreCase = true) }
          .minByOrNull { it.createdAt }

  override suspend fun findById(id: Long): CharRef? = chars[id]

  override suspend fun friends(charId: Long): List<FriendRow> =
      friends.entries
          .filter { it.key.first == charId }
          .mapNotNull { (k, since) ->
            chars[k.second]?.let { FriendRow(it, since, (k.second to charId) in friends) }
          }
          .sortedBy { it.friend.name.lowercase() }

  override suspend fun friendedBy(charId: Long): List<Long> =
      friends.keys.filter { it.second == charId }.map { it.first }

  override suspend fun addFriend(charId: Long, friendId: Long, now: LocalDateTime): Boolean =
      friends.putIfAbsent(charId to friendId, now) == null

  override suspend fun removeFriend(charId: Long, friendId: Long): Boolean =
      friends.remove(charId to friendId) != null

  override suspend fun blocked(charId: Long): List<CharRef> =
      blocks.keys.filter { it.first == charId }.mapNotNull { chars[it.second] }

  override suspend fun block(charId: Long, blockedId: Long, now: LocalDateTime): Boolean =
      blocks.putIfAbsent(charId to blockedId, now) == null

  override suspend fun unblock(charId: Long, blockedId: Long): Boolean =
      blocks.remove(charId to blockedId) != null

  override suspend fun guildOf(charId: Long): GuildRow? =
      members[charId]?.let { guilds[it.guildId] }

  override suspend fun guildById(guildId: Long): GuildRow? = guilds[guildId]

  override suspend fun guildMembers(guildId: Long): List<GuildMemberRow> =
      members.entries
          .filter { it.value.guildId == guildId }
          .mapNotNull { (id, m) -> chars[id]?.let { GuildMemberRow(it, m.rank, m.joinedAt) } }
          .sortedWith(
              compareByDescending<GuildMemberRow> { it.rank }.thenBy { it.member.name.lowercase() })

  override suspend fun createGuild(
      name: String,
      tag: String,
      leaderId: Long,
      leaderRank: Int,
      now: LocalDateTime,
  ): GuildRow? {
    if (guilds.values.any { it.name.equals(name, ignoreCase = true) } || leaderId in members)
        return null
    val row = GuildRow(ids.getAndIncrement(), name, tag, "", now)
    guilds[row.id] = row
    members[leaderId] = Member(row.id, leaderRank, now)
    return row
  }

  override suspend fun addMember(
      guildId: Long,
      charId: Long,
      rank: Int,
      now: LocalDateTime
  ): Boolean {
    if (charId in members || guildId !in guilds) return false
    members[charId] = Member(guildId, rank, now)
    return true
  }

  override suspend fun removeMember(guildId: Long, charId: Long): Boolean {
    if (members[charId]?.guildId != guildId) return false
    members.remove(charId)
    return true
  }

  override suspend fun setRank(guildId: Long, charId: Long, rank: Int): Boolean {
    val m = members[charId]?.takeIf { it.guildId == guildId } ?: return false
    members[charId] = m.copy(rank = rank)
    return true
  }

  override suspend fun transferLeadership(
      guildId: Long,
      fromId: Long,
      toId: Long,
      leaderRank: Int,
      formerLeaderRank: Int,
  ): Boolean {
    if (members[fromId]?.guildId != guildId || members[toId]?.guildId != guildId) return false
    members[fromId] = members[fromId]!!.copy(rank = formerLeaderRank)
    members[toId] = members[toId]!!.copy(rank = leaderRank)
    return true
  }

  override suspend fun setMotd(guildId: Long, motd: String) {
    guilds[guildId]?.let { guilds[guildId] = it.copy(motd = motd) }
  }

  override suspend fun disband(guildId: Long) {
    guilds.remove(guildId)
    members.entries.removeIf { it.value.guildId == guildId }
    logs.removeIf { it.first == guildId }
  }

  override suspend fun addGuildLog(guildId: Long, text: String, now: LocalDateTime) {
    logs += guildId to GuildLogRow(now, text)
  }

  override suspend fun guildLog(guildId: Long, limit: Int): List<GuildLogRow> =
      logs.filter { it.first == guildId }.map { it.second }.reversed().take(limit)

  override suspend fun recordTrade(
      aId: Long,
      bId: Long,
      gaveA: String,
      gaveB: String,
      now: LocalDateTime
  ) {
    trades += Trade(now, aId, bId, gaveA, gaveB)
  }

  override suspend fun tradeHistory(charId: Long, limit: Int): List<TradeHistoryRow> =
      trades
          .filter { it.a == charId || it.b == charId }
          .reversed()
          .take(limit)
          .map {
            val iAmA = it.a == charId
            TradeHistoryRow(
                it.at,
                chars[if (iAmA) it.b else it.a]?.name ?: "?",
                if (iAmA) it.gaveA else it.gaveB,
                if (iAmA) it.gaveB else it.gaveA)
          }

  override suspend fun tradeCount(charId: Long): Int =
      trades.count { it.a == charId || it.b == charId }
}
