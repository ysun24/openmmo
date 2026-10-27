package de.fiereu.openmmo.net.game.packets.social

import de.fiereu.bytecodec.Bool
import de.fiereu.bytecodec.Codec
import de.fiereu.bytecodec.CodecScope
import de.fiereu.bytecodec.MalformedPacketException
import de.fiereu.bytecodec.PacketCodec
import de.fiereu.bytecodec.ReadBuffer
import de.fiereu.bytecodec.S32LE
import de.fiereu.bytecodec.S64LE
import de.fiereu.bytecodec.S8
import de.fiereu.bytecodec.U16LE
import de.fiereu.bytecodec.U8
import de.fiereu.bytecodec.Utf16LeNullTerminated
import de.fiereu.bytecodec.WriteBuffer
import de.fiereu.bytecodec.listPrefixed

/*
 * OpenMMO-only social, chat, guild and market-extra packets (branch r3-social). Nothing here is
 * reverse engineered: both ends belong to this project. The retail friend and guild packets carry
 * appearance blobs and unknown fields we cannot fill meaningfully, so the web/desktop clients use
 * these instead. Opcodes: c2s 0xFC/0xFD/0xFE and s2c 0xFD, all free in both directions.
 */

// ---------------------------------------------------------------- c2s

/** What a [SocialRequestPacket] asks for. Append only, the ordinal is the wire value. */
enum class SocialOp {
  /** Resend the friend list, blocks and guild. */
  REFRESH,
  FRIEND_ADD,
  FRIEND_REMOVE,
  BLOCK,
  UNBLOCK,
  /** [SocialRequestPacket.name] is the character whose profile is wanted. */
  PROFILE,
  /** [SocialRequestPacket.arg] is a [ChatChannel] ordinal, [name] the whisper target. */
  CHAT,
  /** [name] is the guild name, [text] its tag. */
  GUILD_CREATE,
  GUILD_INVITE,
  /** [arg] 1 accepts the pending invite, 0 declines it. */
  GUILD_ANSWER,
  GUILD_LEAVE,
  GUILD_KICK,
  /** [name] gets rank [arg] (a GuildRank ordinal). BOSS hands over the leadership. */
  GUILD_RANK,
  GUILD_MOTD,
  GUILD_DISBAND,
  TRADE_HISTORY,
}

/** c2s 0xFC: one social action. Unused fields are empty / 0. */
data class SocialRequestPacket(
    val op: SocialOp,
    val name: String = "",
    val text: String = "",
    val arg: Int = 0,
)

object SocialRequestPacketCodec : PacketCodec<SocialRequestPacket>() {
  override fun CodecScope<SocialRequestPacket>.body(): SocialRequestPacket {
    val op = enumOf<SocialOp>(field(U8) { it.op.ordinal })
    val name = field(Utf16LeNullTerminated) { it.name }
    val text = field(Utf16LeNullTerminated) { it.text }
    val arg = field(S32LE) { it.arg }
    return SocialRequestPacket(op, name, text, arg)
  }
}

enum class MarketQueryOp {
  /** Sales of one species ([MarketQueryPacket.kind] 0) or item (1) id. */
  PRICE_HISTORY,
  /** The sender's own sold listings. */
  SALES_HISTORY,
  /** Fees and limits. */
  INFO,
}

/** c2s 0xFD: market data that is not a listing page. */
data class MarketQueryPacket(val op: MarketQueryOp, val kind: Byte = 0, val refId: Int = 0)

object MarketQueryPacketCodec : PacketCodec<MarketQueryPacket>() {
  override fun CodecScope<MarketQueryPacket>.body(): MarketQueryPacket {
    val op = enumOf<MarketQueryOp>(field(U8) { it.op.ordinal })
    val kind = field(S8) { it.kind }
    val refId = field(S32LE) { it.refId }
    return MarketQueryPacket(op, kind, refId)
  }
}

/** c2s 0xFE: toggles the monster in PC slot [pcSlot] on or off the trade table. */
data class TradeOfferPcMonPacket(val pcSlot: Int)

object TradeOfferPcMonPacketCodec : PacketCodec<TradeOfferPcMonPacket>() {
  override fun CodecScope<TradeOfferPcMonPacket>.body(): TradeOfferPcMonPacket =
      TradeOfferPcMonPacket(field(S32LE) { it.pcSlot })
}

// ---------------------------------------------------------------- s2c

enum class ChatChannel {
  /** Everyone on the same map. */
  LOCAL,
  GLOBAL,
  TRADE,
  GUILD,
  WHISPER,
  /** Server notices about the channel itself (rate limited, player offline...). */
  SYSTEM,
}

data class MapRef(val regionId: Int = -1, val bankId: Int = -1, val mapId: Int = -1) {
  val known: Boolean
    get() = regionId >= 0

  companion object {
    val UNKNOWN = MapRef()
  }
}

data class FriendView(
    val name: String,
    val online: Boolean,
    val location: MapRef,
    val sinceEpoch: Long,
    /** They have us on their list too. */
    val mutual: Boolean,
)

data class ProfileMon(val dexId: Int, val level: Int, val shiny: Boolean)

data class GuildMemberView(
    val name: String,
    val rank: Int,
    val online: Boolean,
    val location: MapRef,
    val joinedEpoch: Long,
)

data class GuildLogView(val epoch: Long, val text: String)

data class ListingMeta(val listingId: Long, val sellerName: String)

data class PricePoint(
    val dayEpoch: Long,
    val min: Int,
    val max: Int,
    val avg: Int,
    val volume: Int,
)

data class RecentSale(val epoch: Long, val unitPrice: Int, val quantity: Int)

data class SaleRecord(
    val listingId: Long,
    val kind: Byte,
    val refId: Int,
    val quantity: Int,
    val price: Int,
    val fee: Int,
    val buyerName: String,
    val epoch: Long,
)

data class TradeRecord(val epoch: Long, val partner: String, val gave: String, val got: String)

/** One s2c community message. Wrapped in [CommunityPacket] so the protocol has one type. */
sealed interface CommunityBody {
  data class Friends(val friends: List<FriendView>, val blocked: List<String>) : CommunityBody

  data class Profile(
      val name: String,
      val online: Boolean,
      val location: MapRef,
      val guildName: String,
      val guildTag: String,
      val party: List<ProfileMon>,
      val createdEpoch: Long,
      val friend: Boolean,
      val blocked: Boolean,
      val trades: Int,
  ) : CommunityBody

  data class Chat(
      val channel: ChatChannel,
      val from: String,
      val to: String,
      val text: String,
      val epoch: Long,
  ) : CommunityBody

  data class Guild(
      val inGuild: Boolean,
      val name: String = "",
      val tag: String = "",
      val motd: String = "",
      val myRank: Int = 0,
      val members: List<GuildMemberView> = emptyList(),
      val log: List<GuildLogView> = emptyList(),
  ) : CommunityBody

  /** A pending invite; an empty [guildName] withdraws it. */
  data class GuildInvite(
      val from: String,
      val guildName: String,
      val guildTag: String,
      val expiresInSeconds: Int,
  ) : CommunityBody

  /** Seller names for listings of the page sent just before. */
  data class ListingMetas(val entries: List<ListingMeta>) : CommunityBody

  data class PriceHistory(
      val kind: Byte,
      val refId: Int,
      val days: List<PricePoint>,
      val recent: List<RecentSale>,
  ) : CommunityBody

  data class SalesHistory(val entries: List<SaleRecord>) : CommunityBody

  data class MarketInfo(
      val feePermille: Int,
      val minFee: Int,
      val maxActive: Int,
      val active: Int,
      val listingHours: Int,
  ) : CommunityBody

  /** Open the market window (the player talked to a market clerk). */
  data object MarketOpen : CommunityBody

  data class TradeHistory(val entries: List<TradeRecord>) : CommunityBody
}

/** s2c 0xFD. */
data class CommunityPacket(val body: CommunityBody)

private inline fun <reified E : Enum<E>> enumOf(ordinal: Int): E =
    enumValues<E>().getOrNull(ordinal)
        ?: throw MalformedPacketException("bad ${E::class.simpleName}")

private val Str = Utf16LeNullTerminated

private object MapRefCodec : Codec<MapRef> {
  override fun read(buf: ReadBuffer) = MapRef(S32LE.read(buf), S32LE.read(buf), S32LE.read(buf))

  override fun write(buf: WriteBuffer, value: MapRef) {
    S32LE.write(buf, value.regionId)
    S32LE.write(buf, value.bankId)
    S32LE.write(buf, value.mapId)
  }
}

/** A codec from a read lambda and a write lambda, to keep the record codecs below short. */
private fun <T> codec(read: (ReadBuffer) -> T, write: (WriteBuffer, T) -> Unit): Codec<T> =
    object : Codec<T> {
      override fun read(buf: ReadBuffer): T = read(buf)

      override fun write(buf: WriteBuffer, value: T) = write(buf, value)
    }

private fun <T> Codec<T>.list(): Codec<List<T>> = listPrefixed(U16LE)

private val FriendViewCodec =
    codec(
        { b ->
          FriendView(Str.read(b), Bool.read(b), MapRefCodec.read(b), S64LE.read(b), Bool.read(b))
        },
        { b, v ->
          Str.write(b, v.name)
          Bool.write(b, v.online)
          MapRefCodec.write(b, v.location)
          S64LE.write(b, v.sinceEpoch)
          Bool.write(b, v.mutual)
        })

private val ProfileMonCodec =
    codec(
        { b -> ProfileMon(U16LE.read(b), U8.read(b), Bool.read(b)) },
        { b, v ->
          U16LE.write(b, v.dexId)
          U8.write(b, v.level)
          Bool.write(b, v.shiny)
        })

private val GuildMemberCodec =
    codec(
        { b ->
          GuildMemberView(Str.read(b), U8.read(b), Bool.read(b), MapRefCodec.read(b), S64LE.read(b))
        },
        { b, v ->
          Str.write(b, v.name)
          U8.write(b, v.rank)
          Bool.write(b, v.online)
          MapRefCodec.write(b, v.location)
          S64LE.write(b, v.joinedEpoch)
        })

private val GuildLogCodec =
    codec(
        { b -> GuildLogView(S64LE.read(b), Str.read(b)) },
        { b, v ->
          S64LE.write(b, v.epoch)
          Str.write(b, v.text)
        })

private val ListingMetaCodec =
    codec(
        { b -> ListingMeta(S64LE.read(b), Str.read(b)) },
        { b, v ->
          S64LE.write(b, v.listingId)
          Str.write(b, v.sellerName)
        })

private val PricePointCodec =
    codec(
        { b ->
          PricePoint(S64LE.read(b), S32LE.read(b), S32LE.read(b), S32LE.read(b), S32LE.read(b))
        },
        { b, v ->
          S64LE.write(b, v.dayEpoch)
          S32LE.write(b, v.min)
          S32LE.write(b, v.max)
          S32LE.write(b, v.avg)
          S32LE.write(b, v.volume)
        })

private val RecentSaleCodec =
    codec(
        { b -> RecentSale(S64LE.read(b), S32LE.read(b), S32LE.read(b)) },
        { b, v ->
          S64LE.write(b, v.epoch)
          S32LE.write(b, v.unitPrice)
          S32LE.write(b, v.quantity)
        })

private val SaleRecordCodec =
    codec(
        { b ->
          SaleRecord(
              S64LE.read(b),
              S8.read(b),
              S32LE.read(b),
              S32LE.read(b),
              S32LE.read(b),
              S32LE.read(b),
              Str.read(b),
              S64LE.read(b))
        },
        { b, v ->
          S64LE.write(b, v.listingId)
          S8.write(b, v.kind)
          S32LE.write(b, v.refId)
          S32LE.write(b, v.quantity)
          S32LE.write(b, v.price)
          S32LE.write(b, v.fee)
          Str.write(b, v.buyerName)
          S64LE.write(b, v.epoch)
        })

private val TradeRecordCodec =
    codec(
        { b -> TradeRecord(S64LE.read(b), Str.read(b), Str.read(b), Str.read(b)) },
        { b, v ->
          S64LE.write(b, v.epoch)
          Str.write(b, v.partner)
          Str.write(b, v.gave)
          Str.write(b, v.got)
        })

private val StrList = Str.list()

object CommunityPacketCodec : Codec<CommunityPacket> {
  private const val FRIENDS = 1
  private const val PROFILE = 2
  private const val CHAT = 3
  private const val GUILD = 4
  private const val GUILD_INVITE = 5
  private const val LISTING_METAS = 6
  private const val PRICE_HISTORY = 7
  private const val SALES_HISTORY = 8
  private const val MARKET_INFO = 9
  private const val MARKET_OPEN = 10
  private const val TRADE_HISTORY = 11

  override fun read(buf: ReadBuffer): CommunityPacket {
    val body: CommunityBody =
        when (val tag = U8.read(buf)) {
          FRIENDS -> CommunityBody.Friends(FriendViewCodec.list().read(buf), StrList.read(buf))
          PROFILE ->
              CommunityBody.Profile(
                  Str.read(buf),
                  Bool.read(buf),
                  MapRefCodec.read(buf),
                  Str.read(buf),
                  Str.read(buf),
                  ProfileMonCodec.list().read(buf),
                  S64LE.read(buf),
                  Bool.read(buf),
                  Bool.read(buf),
                  S32LE.read(buf))
          CHAT ->
              CommunityBody.Chat(
                  enumOf(U8.read(buf)),
                  Str.read(buf),
                  Str.read(buf),
                  Str.read(buf),
                  S64LE.read(buf))
          GUILD ->
              CommunityBody.Guild(
                  Bool.read(buf),
                  Str.read(buf),
                  Str.read(buf),
                  Str.read(buf),
                  U8.read(buf),
                  GuildMemberCodec.list().read(buf),
                  GuildLogCodec.list().read(buf))
          GUILD_INVITE ->
              CommunityBody.GuildInvite(
                  Str.read(buf), Str.read(buf), Str.read(buf), S32LE.read(buf))
          LISTING_METAS -> CommunityBody.ListingMetas(ListingMetaCodec.list().read(buf))
          PRICE_HISTORY ->
              CommunityBody.PriceHistory(
                  S8.read(buf),
                  S32LE.read(buf),
                  PricePointCodec.list().read(buf),
                  RecentSaleCodec.list().read(buf))
          SALES_HISTORY -> CommunityBody.SalesHistory(SaleRecordCodec.list().read(buf))
          MARKET_INFO ->
              CommunityBody.MarketInfo(
                  S32LE.read(buf),
                  S32LE.read(buf),
                  S32LE.read(buf),
                  S32LE.read(buf),
                  S32LE.read(buf))
          MARKET_OPEN -> CommunityBody.MarketOpen
          TRADE_HISTORY -> CommunityBody.TradeHistory(TradeRecordCodec.list().read(buf))
          else -> throw MalformedPacketException("unknown community tag $tag")
        }
    return CommunityPacket(body)
  }

  override fun write(buf: WriteBuffer, value: CommunityPacket) {
    when (val v = value.body) {
      is CommunityBody.Friends -> {
        U8.write(buf, FRIENDS)
        FriendViewCodec.list().write(buf, v.friends)
        StrList.write(buf, v.blocked)
      }
      is CommunityBody.Profile -> {
        U8.write(buf, PROFILE)
        Str.write(buf, v.name)
        Bool.write(buf, v.online)
        MapRefCodec.write(buf, v.location)
        Str.write(buf, v.guildName)
        Str.write(buf, v.guildTag)
        ProfileMonCodec.list().write(buf, v.party)
        S64LE.write(buf, v.createdEpoch)
        Bool.write(buf, v.friend)
        Bool.write(buf, v.blocked)
        S32LE.write(buf, v.trades)
      }
      is CommunityBody.Chat -> {
        U8.write(buf, CHAT)
        U8.write(buf, v.channel.ordinal)
        Str.write(buf, v.from)
        Str.write(buf, v.to)
        Str.write(buf, v.text)
        S64LE.write(buf, v.epoch)
      }
      is CommunityBody.Guild -> {
        U8.write(buf, GUILD)
        Bool.write(buf, v.inGuild)
        Str.write(buf, v.name)
        Str.write(buf, v.tag)
        Str.write(buf, v.motd)
        U8.write(buf, v.myRank)
        GuildMemberCodec.list().write(buf, v.members)
        GuildLogCodec.list().write(buf, v.log)
      }
      is CommunityBody.GuildInvite -> {
        U8.write(buf, GUILD_INVITE)
        Str.write(buf, v.from)
        Str.write(buf, v.guildName)
        Str.write(buf, v.guildTag)
        S32LE.write(buf, v.expiresInSeconds)
      }
      is CommunityBody.ListingMetas -> {
        U8.write(buf, LISTING_METAS)
        ListingMetaCodec.list().write(buf, v.entries)
      }
      is CommunityBody.PriceHistory -> {
        U8.write(buf, PRICE_HISTORY)
        S8.write(buf, v.kind)
        S32LE.write(buf, v.refId)
        PricePointCodec.list().write(buf, v.days)
        RecentSaleCodec.list().write(buf, v.recent)
      }
      is CommunityBody.SalesHistory -> {
        U8.write(buf, SALES_HISTORY)
        SaleRecordCodec.list().write(buf, v.entries)
      }
      is CommunityBody.MarketInfo -> {
        U8.write(buf, MARKET_INFO)
        S32LE.write(buf, v.feePermille)
        S32LE.write(buf, v.minFee)
        S32LE.write(buf, v.maxActive)
        S32LE.write(buf, v.active)
        S32LE.write(buf, v.listingHours)
      }
      CommunityBody.MarketOpen -> U8.write(buf, MARKET_OPEN)
      is CommunityBody.TradeHistory -> {
        U8.write(buf, TRADE_HISTORY)
        TradeRecordCodec.list().write(buf, v.entries)
      }
    }
  }
}
