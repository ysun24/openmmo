package de.fiereu.openmmo.server.game.storage

import de.fiereu.openmmo.db.game.tables.references.BLOCKED_PLAYERS
import de.fiereu.openmmo.db.game.tables.references.CHARACTERS
import de.fiereu.openmmo.db.game.tables.references.FRIENDS
import de.fiereu.openmmo.db.game.tables.references.GUILDS
import de.fiereu.openmmo.db.game.tables.references.GUILD_LOG
import de.fiereu.openmmo.db.game.tables.references.GUILD_MEMBERS
import de.fiereu.openmmo.db.game.tables.references.TRADE_HISTORY
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL

/** A character as the social features see it: who, not what it carries. */
data class CharRef(val id: Long, val name: String, val userId: Int, val createdAt: LocalDateTime)

data class FriendRow(val friend: CharRef, val since: LocalDateTime, val mutual: Boolean)

data class GuildRow(
    val id: Long,
    val name: String,
    val tag: String,
    val motd: String,
    val createdAt: LocalDateTime,
)

data class GuildMemberRow(val member: CharRef, val rank: Int, val joinedAt: LocalDateTime)

data class GuildLogRow(val at: LocalDateTime, val text: String)

data class TradeHistoryRow(
    val at: LocalDateTime,
    val partner: String,
    val gave: String,
    val got: String,
)

/** Friends, blocks, guilds and the trade log. Everything is keyed by character id. */
interface SocialRepository {
  /** Case-insensitive; the oldest character when two share a name. */
  suspend fun findByName(name: String): CharRef?

  suspend fun findById(id: Long): CharRef?

  suspend fun friends(charId: Long): List<FriendRow>

  /** Characters that have [charId] on their friend list. */
  suspend fun friendedBy(charId: Long): List<Long>

  /** False when already a friend. */
  suspend fun addFriend(charId: Long, friendId: Long, now: LocalDateTime): Boolean

  suspend fun removeFriend(charId: Long, friendId: Long): Boolean

  suspend fun blocked(charId: Long): List<CharRef>

  suspend fun block(charId: Long, blockedId: Long, now: LocalDateTime): Boolean

  suspend fun unblock(charId: Long, blockedId: Long): Boolean

  suspend fun guildOf(charId: Long): GuildRow?

  suspend fun guildById(guildId: Long): GuildRow?

  suspend fun guildMembers(guildId: Long): List<GuildMemberRow>

  /** Null when the name is taken (case-insensitively) or [leaderId] already is in a guild. */
  suspend fun createGuild(
      name: String,
      tag: String,
      leaderId: Long,
      leaderRank: Int,
      now: LocalDateTime,
  ): GuildRow?

  /** False when [charId] already is in a guild. */
  suspend fun addMember(guildId: Long, charId: Long, rank: Int, now: LocalDateTime): Boolean

  suspend fun removeMember(guildId: Long, charId: Long): Boolean

  suspend fun setRank(guildId: Long, charId: Long, rank: Int): Boolean

  /** Gives [toId] [leaderRank] and drops [fromId] to [formerLeaderRank], atomically. */
  suspend fun transferLeadership(
      guildId: Long,
      fromId: Long,
      toId: Long,
      leaderRank: Int,
      formerLeaderRank: Int,
  ): Boolean

  suspend fun setMotd(guildId: Long, motd: String)

  suspend fun disband(guildId: Long)

  suspend fun addGuildLog(guildId: Long, text: String, now: LocalDateTime)

  /** Newest first. */
  suspend fun guildLog(guildId: Long, limit: Int): List<GuildLogRow>

  suspend fun recordTrade(aId: Long, bId: Long, gaveA: String, gaveB: String, now: LocalDateTime)

  /** Newest first, from [charId]'s side. */
  suspend fun tradeHistory(charId: Long, limit: Int): List<TradeHistoryRow>

  suspend fun tradeCount(charId: Long): Int
}

@Singleton
class JooqSocialRepository
@Inject
constructor(
    private val dsl: DSLContext,
    @param:Named("db") private val dispatcher: CoroutineDispatcher,
) : SocialRepository {

  private suspend fun <T> db(block: (DSLContext) -> T): T = withContext(dispatcher) { block(dsl) }

  private fun Record.charRef(): CharRef =
      CharRef(
          get(CHARACTERS.ID)!!,
          get(CHARACTERS.NAME)!!,
          get(CHARACTERS.USER_ID)!!,
          get(CHARACTERS.CREATED_AT)!!)

  private val charFields =
      listOf(CHARACTERS.ID, CHARACTERS.NAME, CHARACTERS.USER_ID, CHARACTERS.CREATED_AT)

  override suspend fun findByName(name: String): CharRef? = db { d ->
    d.select(charFields)
        .from(CHARACTERS)
        .where(DSL.lower(CHARACTERS.NAME).eq(name.trim().lowercase()))
        .orderBy(CHARACTERS.CREATED_AT, CHARACTERS.ID)
        .limit(1)
        .fetchOne()
        ?.charRef()
  }

  override suspend fun findById(id: Long): CharRef? = db { d ->
    d.select(charFields).from(CHARACTERS).where(CHARACTERS.ID.eq(id)).fetchOne()?.charRef()
  }

  override suspend fun friends(charId: Long): List<FriendRow> = db { d ->
    val back = FRIENDS.`as`("back")
    d.select(charFields + listOf(FRIENDS.CREATED_AT, back.CHARACTER_ID))
        .from(FRIENDS)
        .join(CHARACTERS)
        .on(CHARACTERS.ID.eq(FRIENDS.FRIEND_ID))
        .leftJoin(back)
        .on(back.CHARACTER_ID.eq(FRIENDS.FRIEND_ID).and(back.FRIEND_ID.eq(FRIENDS.CHARACTER_ID)))
        .where(FRIENDS.CHARACTER_ID.eq(charId))
        .orderBy(DSL.lower(CHARACTERS.NAME))
        .fetch { r ->
          FriendRow(r.charRef(), r.get(FRIENDS.CREATED_AT)!!, r.get(back.CHARACTER_ID) != null)
        }
  }

  override suspend fun friendedBy(charId: Long): List<Long> = db { d ->
    d.select(FRIENDS.CHARACTER_ID)
        .from(FRIENDS)
        .where(FRIENDS.FRIEND_ID.eq(charId))
        .fetch(FRIENDS.CHARACTER_ID)
        .filterNotNull()
  }

  override suspend fun addFriend(charId: Long, friendId: Long, now: LocalDateTime): Boolean =
      db { d ->
        d.insertInto(FRIENDS)
            .set(FRIENDS.CHARACTER_ID, charId)
            .set(FRIENDS.FRIEND_ID, friendId)
            .set(FRIENDS.CREATED_AT, now)
            .onConflictDoNothing()
            .execute() == 1
      }

  override suspend fun removeFriend(charId: Long, friendId: Long): Boolean = db { d ->
    d.deleteFrom(FRIENDS)
        .where(FRIENDS.CHARACTER_ID.eq(charId))
        .and(FRIENDS.FRIEND_ID.eq(friendId))
        .execute() == 1
  }

  override suspend fun blocked(charId: Long): List<CharRef> = db { d ->
    d.select(charFields)
        .from(BLOCKED_PLAYERS)
        .join(CHARACTERS)
        .on(CHARACTERS.ID.eq(BLOCKED_PLAYERS.BLOCKED_ID))
        .where(BLOCKED_PLAYERS.CHARACTER_ID.eq(charId))
        .orderBy(DSL.lower(CHARACTERS.NAME))
        .fetch { it.charRef() }
  }

  override suspend fun block(charId: Long, blockedId: Long, now: LocalDateTime): Boolean = db { d ->
    d.insertInto(BLOCKED_PLAYERS)
        .set(BLOCKED_PLAYERS.CHARACTER_ID, charId)
        .set(BLOCKED_PLAYERS.BLOCKED_ID, blockedId)
        .set(BLOCKED_PLAYERS.CREATED_AT, now)
        .onConflictDoNothing()
        .execute() == 1
  }

  override suspend fun unblock(charId: Long, blockedId: Long): Boolean = db { d ->
    d.deleteFrom(BLOCKED_PLAYERS)
        .where(BLOCKED_PLAYERS.CHARACTER_ID.eq(charId))
        .and(BLOCKED_PLAYERS.BLOCKED_ID.eq(blockedId))
        .execute() == 1
  }

  private fun Record.guildRow(): GuildRow =
      GuildRow(
          get(GUILDS.ID)!!,
          get(GUILDS.NAME)!!,
          get(GUILDS.TAG)!!,
          get(GUILDS.MOTD)!!,
          get(GUILDS.CREATED_AT)!!)

  override suspend fun guildOf(charId: Long): GuildRow? = db { d ->
    d.select(GUILDS.asterisk())
        .from(GUILDS)
        .join(GUILD_MEMBERS)
        .on(GUILD_MEMBERS.GUILD_ID.eq(GUILDS.ID))
        .where(GUILD_MEMBERS.CHARACTER_ID.eq(charId))
        .fetchOne()
        ?.guildRow()
  }

  override suspend fun guildById(guildId: Long): GuildRow? = db { d ->
    d.selectFrom(GUILDS).where(GUILDS.ID.eq(guildId)).fetchOne()?.guildRow()
  }

  override suspend fun guildMembers(guildId: Long): List<GuildMemberRow> = db { d ->
    d.select(charFields + listOf(GUILD_MEMBERS.GUILD_RANK, GUILD_MEMBERS.JOINED_AT))
        .from(GUILD_MEMBERS)
        .join(CHARACTERS)
        .on(CHARACTERS.ID.eq(GUILD_MEMBERS.CHARACTER_ID))
        .where(GUILD_MEMBERS.GUILD_ID.eq(guildId))
        .orderBy(GUILD_MEMBERS.GUILD_RANK.desc(), DSL.lower(CHARACTERS.NAME))
        .fetch { r ->
          GuildMemberRow(
              r.charRef(),
              r.get(GUILD_MEMBERS.GUILD_RANK)!!.toInt(),
              r.get(GUILD_MEMBERS.JOINED_AT)!!)
        }
  }

  override suspend fun createGuild(
      name: String,
      tag: String,
      leaderId: Long,
      leaderRank: Int,
      now: LocalDateTime,
  ): GuildRow? = db { d ->
    d.transactionResult { cfg ->
      val tx = cfg.dsl()
      val taken =
          tx.fetchExists(GUILDS, GUILDS.NAME_KEY.eq(name.lowercase())) ||
              tx.fetchExists(GUILD_MEMBERS, GUILD_MEMBERS.CHARACTER_ID.eq(leaderId))
      if (taken) return@transactionResult null
      val id =
          tx.insertInto(GUILDS)
              .set(GUILDS.NAME, name)
              .set(GUILDS.NAME_KEY, name.lowercase())
              .set(GUILDS.TAG, tag)
              .set(GUILDS.MOTD, "")
              .set(GUILDS.CREATED_AT, now)
              .returning(GUILDS.ID)
              .fetchOne()!!
              .id!!
      tx.insertInto(GUILD_MEMBERS)
          .set(GUILD_MEMBERS.CHARACTER_ID, leaderId)
          .set(GUILD_MEMBERS.GUILD_ID, id)
          .set(GUILD_MEMBERS.GUILD_RANK, leaderRank.toShort())
          .set(GUILD_MEMBERS.JOINED_AT, now)
          .execute()
      GuildRow(id, name, tag, "", now)
    }
  }

  override suspend fun addMember(
      guildId: Long,
      charId: Long,
      rank: Int,
      now: LocalDateTime
  ): Boolean = db { d ->
    d.insertInto(GUILD_MEMBERS)
        .set(GUILD_MEMBERS.CHARACTER_ID, charId)
        .set(GUILD_MEMBERS.GUILD_ID, guildId)
        .set(GUILD_MEMBERS.GUILD_RANK, rank.toShort())
        .set(GUILD_MEMBERS.JOINED_AT, now)
        .onConflictDoNothing()
        .execute() == 1
  }

  override suspend fun removeMember(guildId: Long, charId: Long): Boolean = db { d ->
    d.deleteFrom(GUILD_MEMBERS)
        .where(GUILD_MEMBERS.CHARACTER_ID.eq(charId))
        .and(GUILD_MEMBERS.GUILD_ID.eq(guildId))
        .execute() == 1
  }

  override suspend fun setRank(guildId: Long, charId: Long, rank: Int): Boolean = db { d ->
    d.update(GUILD_MEMBERS)
        .set(GUILD_MEMBERS.GUILD_RANK, rank.toShort())
        .where(GUILD_MEMBERS.CHARACTER_ID.eq(charId))
        .and(GUILD_MEMBERS.GUILD_ID.eq(guildId))
        .execute() == 1
  }

  override suspend fun transferLeadership(
      guildId: Long,
      fromId: Long,
      toId: Long,
      leaderRank: Int,
      formerLeaderRank: Int,
  ): Boolean = db { d ->
    d.transactionResult { cfg ->
      val tx = cfg.dsl()
      val a =
          tx.update(GUILD_MEMBERS)
              .set(GUILD_MEMBERS.GUILD_RANK, formerLeaderRank.toShort())
              .where(GUILD_MEMBERS.CHARACTER_ID.eq(fromId))
              .and(GUILD_MEMBERS.GUILD_ID.eq(guildId))
              .execute()
      val b =
          tx.update(GUILD_MEMBERS)
              .set(GUILD_MEMBERS.GUILD_RANK, leaderRank.toShort())
              .where(GUILD_MEMBERS.CHARACTER_ID.eq(toId))
              .and(GUILD_MEMBERS.GUILD_ID.eq(guildId))
              .execute()
      if (a != 1 || b != 1) throw IllegalStateException("leadership transfer touched $a/$b rows")
      true
    }
  }

  override suspend fun setMotd(guildId: Long, motd: String) {
    db { d -> d.update(GUILDS).set(GUILDS.MOTD, motd).where(GUILDS.ID.eq(guildId)).execute() }
  }

  override suspend fun disband(guildId: Long) {
    db { d -> d.deleteFrom(GUILDS).where(GUILDS.ID.eq(guildId)).execute() }
  }

  override suspend fun addGuildLog(guildId: Long, text: String, now: LocalDateTime) {
    db { d ->
      d.insertInto(GUILD_LOG)
          .set(GUILD_LOG.GUILD_ID, guildId)
          .set(GUILD_LOG.CREATED_AT, now)
          .set(GUILD_LOG.TEXT, text.take(200))
          .execute()
    }
  }

  override suspend fun guildLog(guildId: Long, limit: Int): List<GuildLogRow> = db { d ->
    d.select(GUILD_LOG.CREATED_AT, GUILD_LOG.TEXT)
        .from(GUILD_LOG)
        .where(GUILD_LOG.GUILD_ID.eq(guildId))
        .orderBy(GUILD_LOG.ID.desc())
        .limit(limit)
        .fetch { GuildLogRow(it.value1()!!, it.value2()!!) }
  }

  override suspend fun recordTrade(
      aId: Long,
      bId: Long,
      gaveA: String,
      gaveB: String,
      now: LocalDateTime
  ) {
    db { d ->
      d.insertInto(TRADE_HISTORY)
          .set(TRADE_HISTORY.CREATED_AT, now)
          .set(TRADE_HISTORY.CHARACTER_A, aId)
          .set(TRADE_HISTORY.CHARACTER_B, bId)
          .set(TRADE_HISTORY.GAVE_A, gaveA.take(400))
          .set(TRADE_HISTORY.GAVE_B, gaveB.take(400))
          .execute()
    }
  }

  override suspend fun tradeHistory(charId: Long, limit: Int): List<TradeHistoryRow> = db { d ->
    val other = CHARACTERS.`as`("other")
    d.select(
            TRADE_HISTORY.CREATED_AT,
            TRADE_HISTORY.CHARACTER_A,
            TRADE_HISTORY.GAVE_A,
            TRADE_HISTORY.GAVE_B,
            other.NAME)
        .from(TRADE_HISTORY)
        .join(other)
        .on(
            DSL.`when`(TRADE_HISTORY.CHARACTER_A.eq(charId), TRADE_HISTORY.CHARACTER_B)
                .otherwise(TRADE_HISTORY.CHARACTER_A)
                .eq(other.ID))
        .where(TRADE_HISTORY.CHARACTER_A.eq(charId).or(TRADE_HISTORY.CHARACTER_B.eq(charId)))
        .orderBy(TRADE_HISTORY.ID.desc())
        .limit(limit)
        .fetch { r ->
          val iAmA = r.get(TRADE_HISTORY.CHARACTER_A) == charId
          TradeHistoryRow(
              r.get(TRADE_HISTORY.CREATED_AT)!!,
              r.get(other.NAME)!!,
              gave = if (iAmA) r.get(TRADE_HISTORY.GAVE_A)!! else r.get(TRADE_HISTORY.GAVE_B)!!,
              got = if (iAmA) r.get(TRADE_HISTORY.GAVE_B)!! else r.get(TRADE_HISTORY.GAVE_A)!!,
          )
        }
  }

  override suspend fun tradeCount(charId: Long): Int = db { d ->
    d.fetchCount(
        TRADE_HISTORY,
        TRADE_HISTORY.CHARACTER_A.eq(charId).or(TRADE_HISTORY.CHARACTER_B.eq(charId)))
  }
}
