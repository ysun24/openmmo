package de.fiereu.openmmo.net.game.packets.progress

import de.fiereu.bytecodec.Bool
import de.fiereu.bytecodec.CodecScope
import de.fiereu.bytecodec.PacketCodec
import de.fiereu.bytecodec.S16LE
import de.fiereu.bytecodec.S32LE
import de.fiereu.bytecodec.S64LE
import de.fiereu.bytecodec.U16LE
import de.fiereu.bytecodec.U8
import de.fiereu.bytecodec.bytesPrefixed
import de.fiereu.bytecodec.listPrefixed
import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.net.game.codecs.PokemonCodec

/*
 * OpenMMO-only progression packets (branch r3-items): the pokedex, badges, trainer card, field use
 * of items that need a move, the day care and decoration shops. Nothing captured covers these, so
 * the layouts are ours. c2s 0xF8 and s2c 0xE7 (see GameProtocol).
 */

/** What [ProgressActionPacket] asks for. Ids are stable wire values. */
enum class ProgressAction(val id: Int) {
  /** Use [ProgressActionPacket.itemId] on a move of a monster: Ether, PP Up, Leppa, a TM/HM. */
  USE_ON_MOVE(0),
  /** Answer the learn prompt: arg1 = slot to forget (0-3) or -1 to give up the move. */
  LEARN_REPLY(1),
  /** Register a key item for the select button (itemId 0 clears). */
  REGISTER_ITEM(2),
  /** Use the registered item. */
  USE_REGISTERED(3),
  /** Leave party monster monId at the open day care. */
  DAYCARE_DEPOSIT(4),
  /** Take day care monster arg1 (0 or 1) back, paying its fee. */
  DAYCARE_WITHDRAW(5),
  /** Close the day care picker without choosing. */
  DAYCARE_CANCEL(6),
  /** Buy arg2 of decoration arg1 at the open decoration shop. */
  DECOR_BUY(7),
  /** The mart or decoration shop window was closed ("Please come again!"). */
  SHOP_CLOSED(8),
  /** Send a fresh [ProgressStatePacket]. */
  REFRESH(9);

  companion object {
    fun of(id: Int): ProgressAction? = entries.firstOrNull { it.id == id }
  }
}

data class ProgressActionPacket(
    val action: ProgressAction,
    val itemId: Short = 0,
    val monId: Long = 0,
    val arg1: Int = 0,
    val arg2: Int = 0,
)

object ProgressActionPacketCodec : PacketCodec<ProgressActionPacket>() {
  override fun CodecScope<ProgressActionPacket>.body(): ProgressActionPacket {
    val action = field(U8) { it.action.id }
    val itemId = field(S16LE) { it.itemId }
    val monId = field(S64LE) { it.monId }
    val arg1 = field(S32LE) { it.arg1 }
    val arg2 = field(S32LE) { it.arg2 }
    return ProgressActionPacket(
        ProgressAction.of(action) ?: ProgressAction.REFRESH, itemId, monId, arg1, arg2)
  }
}

/** Status and PP bonuses of one owned monster; the monster codec carries neither. */
data class MonExtra(val monId: Long, val status: Short, val ppBonuses: Int)

data class DecorStack(val decorId: Short, val quantity: Short)

/** A move the player must make room for (Rare Candy, TM): [monId] wants [moveId]. */
data class LearnPrompt(val monId: Long, val moveId: Short)

/** The day care, as far as the player can see it. */
data class DaycareView(
    /** 0 closed, 1 choosing a monster to leave, 2 choosing one to take back. */
    val open: Int,
    val mons: List<Pokemon>,
    /** What taking each of [mons] back costs, in the same order. */
    val fees: List<Int>,
    /** Levels each of [mons] gained since it was left. */
    val levelsGained: List<Int>,
    val eggWaiting: Boolean,
)

/** Everything the r3-items features show. Sent on login and after every change. */
data class ProgressStatePacket(
    /** Bit n of byte n/8 = national dex n seen. */
    val seen: ByteArray,
    val caught: ByteArray,
    /** Bits 0-7 Kanto badges 1-8, bits 8-15 Hoenn badges 1-8. */
    val badges: Int,
    val playSeconds: Int,
    val registeredItem: Short,
    val extras: List<MonExtra>,
    val daycare: DaycareView,
    val decorations: List<DecorStack>,
    /** The open decoration shop's stock, empty when none is open. */
    val decorShop: List<Short>,
    val learn: LearnPrompt?,
    /** The character's permission bits, so the UI can show admin tools. */
    val permissions: Int,
    /** Which region's dex the player is in: 0 Kanto, 1 Hoenn. */
    val region: Int,
) {
  override fun equals(other: Any?): Boolean =
      other is ProgressStatePacket &&
          seen.contentEquals(other.seen) &&
          caught.contentEquals(other.caught) &&
          badges == other.badges &&
          playSeconds == other.playSeconds &&
          registeredItem == other.registeredItem &&
          extras == other.extras &&
          daycare == other.daycare &&
          decorations == other.decorations &&
          decorShop == other.decorShop &&
          learn == other.learn &&
          permissions == other.permissions &&
          region == other.region

  override fun hashCode(): Int = seen.contentHashCode() * 31 + caught.contentHashCode() + badges

  fun isSeen(dex: Int): Boolean = bit(seen, dex)

  fun isCaught(dex: Int): Boolean = bit(caught, dex)

  companion object {
    fun bits(dexIds: Collection<Int>): ByteArray {
      val max = dexIds.maxOrNull() ?: return ByteArray(0)
      val out = ByteArray(max / 8 + 1)
      for (d in dexIds) if (d >= 0) out[d / 8] = (out[d / 8].toInt() or (1 shl (d % 8))).toByte()
      return out
    }

    fun bit(bytes: ByteArray, n: Int): Boolean =
        n >= 0 && n / 8 < bytes.size && (bytes[n / 8].toInt() shr (n % 8)) and 1 == 1

    fun ids(bytes: ByteArray): List<Int> = (0 until bytes.size * 8).filter { bit(bytes, it) }
  }
}

object ProgressStatePacketCodec : PacketCodec<ProgressStatePacket>() {
  private val Bytes = bytesPrefixed(U16LE)
  private val ExtraCodec =
      object : PacketCodec<MonExtra>() {
        override fun CodecScope<MonExtra>.body(): MonExtra {
          val id = field(S64LE) { it.monId }
          val status = field(S16LE) { it.status }
          val bonuses = field(U8) { it.ppBonuses }
          return MonExtra(id, status, bonuses)
        }
      }
  private val DecorCodec =
      object : PacketCodec<DecorStack>() {
        override fun CodecScope<DecorStack>.body(): DecorStack {
          val id = field(S16LE) { it.decorId }
          val qty = field(S16LE) { it.quantity }
          return DecorStack(id, qty)
        }
      }
  private val DaycareCodec =
      object : PacketCodec<DaycareView>() {
        override fun CodecScope<DaycareView>.body(): DaycareView {
          val open = field(U8) { it.open }
          val mons = field(PokemonCodec.listPrefixed(U8)) { it.mons }
          val fees = field(S32LE.listPrefixed(U8)) { it.fees }
          val levels = field(S32LE.listPrefixed(U8)) { it.levelsGained }
          val egg = field(Bool) { it.eggWaiting }
          return DaycareView(open, mons, fees, levels, egg)
        }
      }

  override fun CodecScope<ProgressStatePacket>.body(): ProgressStatePacket {
    val seen = field(Bytes) { it.seen }
    val caught = field(Bytes) { it.caught }
    val badges = field(S32LE) { it.badges }
    val playSeconds = field(S32LE) { it.playSeconds }
    val registered = field(S16LE) { it.registeredItem }
    val extras = field(ExtraCodec.listPrefixed(U16LE)) { it.extras }
    val daycare = field(DaycareCodec) { it.daycare }
    val decorations = field(DecorCodec.listPrefixed(U16LE)) { it.decorations }
    val decorShop = field(S16LE.listPrefixed(U16LE)) { it.decorShop }
    val hasLearn = field(Bool) { it.learn != null }
    val learnMon = field(S64LE) { it.learn?.monId ?: 0 }
    val learnMove = field(S16LE) { it.learn?.moveId ?: 0 }
    val permissions = field(S32LE) { it.permissions }
    val region = field(U8) { it.region }
    return ProgressStatePacket(
        seen,
        caught,
        badges,
        playSeconds,
        registered,
        extras,
        daycare,
        decorations,
        decorShop,
        if (hasLearn) LearnPrompt(learnMon, learnMove) else null,
        permissions,
        region,
    )
  }
}
