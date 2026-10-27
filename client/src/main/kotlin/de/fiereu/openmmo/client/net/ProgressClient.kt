package de.fiereu.openmmo.client.net

import de.fiereu.openmmo.net.game.packets.progress.ProgressAction
import de.fiereu.openmmo.net.game.packets.progress.ProgressActionPacket
import de.fiereu.openmmo.net.game.packets.progress.ProgressStatePacket

/**
 * The r3-items progression the server pushes in [ProgressStatePacket] (pokedex, badges, trainer
 * card numbers, monster status / PP bonuses, day care, decorations, a move waiting to be learned),
 * and the [ProgressActionPacket]s that act on it.
 */
class ProgressClient(
    private val send: (Any) -> Unit,
    private val onChange: () -> Unit,
) {
  @Volatile var state: ProgressStatePacket? = null
    private set

  /** When [state] arrived, so play time keeps ticking between pushes. */
  @Volatile var receivedAtMs: Long = 0
    private set

  fun onState(p: ProgressStatePacket) {
    state = p
    receivedAtMs = System.currentTimeMillis()
    onChange()
  }

  /** Seconds played, counting the time since the last push. */
  fun playSeconds(nowMs: Long = System.currentTimeMillis()): Int {
    val s = state ?: return 0
    return s.playSeconds + ((nowMs - receivedAtMs) / 1000).toInt().coerceAtLeast(0)
  }

  private fun act(
      action: ProgressAction,
      itemId: Int = 0,
      monId: Long = 0,
      arg1: Int = 0,
      arg2: Int = 0,
  ) = send(ProgressActionPacket(action, itemId.toShort(), monId, arg1, arg2))

  /** Ether, PP Up, Leppa: [moveSlot] of the monster. A TM: [moveSlot] is the move to forget. */
  fun useOnMove(itemId: Int, monId: Long, moveSlot: Int) =
      act(ProgressAction.USE_ON_MOVE, itemId, monId, moveSlot, moveSlot)

  fun learnReply(forgetSlot: Int?) = act(ProgressAction.LEARN_REPLY, arg1 = forgetSlot ?: -1)

  fun register(itemId: Int) = act(ProgressAction.REGISTER_ITEM, itemId)

  fun useRegistered() = act(ProgressAction.USE_REGISTERED)

  fun daycareDeposit(monId: Long) = act(ProgressAction.DAYCARE_DEPOSIT, monId = monId)

  fun daycareWithdraw(index: Int) = act(ProgressAction.DAYCARE_WITHDRAW, arg1 = index)

  fun daycareCancel() = act(ProgressAction.DAYCARE_CANCEL)

  fun decorBuy(decorId: Int, quantity: Int) =
      act(ProgressAction.DECOR_BUY, arg1 = decorId, arg2 = quantity)

  fun shopClosed() = act(ProgressAction.SHOP_CLOSED)

  fun refresh() = act(ProgressAction.REFRESH)
}

/** What a server notice is about, so a UI can show only what matters. */
enum class NoticeKind {
  /** A player joined or left: chat only. */
  PRESENCE,
  /** Server announcements. */
  SYSTEM,
  /** Something gained: an item, a badge, money, a level, an evolution, a hatch. */
  REWARD,
  /** A refusal or failure. */
  ERROR,
  /** Anything else worth a short toast. */
  INFO;

  val wire: String
    get() = name.lowercase()

  companion object {
    private val PRESENCE_RE = Regex("""^(Player .+|A player) (joined|left) the game\.?$""")
    private val REWARD_RE =
        Regex(
            """\b(received|obtained|got|gave|learned|grew to|evolved|hatched|restored|revived|""" +
                """sold|bought|rose|increased|cured|was sent|registered|made you|is now)\b""",
            RegexOption.IGNORE_CASE)
    private val ERROR_RE =
        Regex(
            """\b(can't|cannot|won't|not |no |don't|isn't|refused|unknown|full|failed|""" +
                """could not|choose|usage)\b""",
            RegexOption.IGNORE_CASE)

    /** Classifies a sender-less chat line ([system] = a SYSTEM_ANNOUNCEMENTS line). */
    fun classify(text: String, system: Boolean = false): NoticeKind =
        when {
          PRESENCE_RE.matches(text.trim()) -> PRESENCE
          system -> SYSTEM
          ERROR_RE.containsMatchIn(text) -> ERROR
          REWARD_RE.containsMatchIn(text) -> REWARD
          else -> INFO
        }
  }
}
