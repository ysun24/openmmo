package de.fiereu.openmmo.client.net

import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.net.game.packets.ChatMessageSendPacket
import de.fiereu.openmmo.net.game.packets.LocalPlayerStatePacket
import de.fiereu.openmmo.net.game.packets.PokemonContainerPacket
import de.fiereu.openmmo.net.game.packets.TradeActionPacket
import de.fiereu.openmmo.net.game.packets.TradeSelectMonPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleSidePartyPacket
import de.fiereu.openmmo.net.game.packets.trade.TradeAction
import de.fiereu.openmmo.net.game.packets.trade.TradeOfferItemPacket
import de.fiereu.openmmo.net.game.packets.trade.TradeOfferKind
import de.fiereu.openmmo.net.game.packets.trade.TradeOfferView
import de.fiereu.openmmo.net.game.packets.trade.TradePhase
import de.fiereu.openmmo.net.game.packets.trade.TradeRequestPacket
import de.fiereu.openmmo.net.game.packets.trade.TradeStatePacket
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

// Bag stacks ride the battle-side party packet; their object ids carry this tag.
private const val ITEM_ENTITY_TAG = 0x5000L

/** Names for ids, shared by the CLI and the trade window. */
object GameNames {
  private val species by lazy { SpeciesRegistry() }
  private val items by lazy { ItemRegistry() }

  /** Display form: the decomp names are upper case ("BULBASAUR"). */
  fun species(dexId: Int): String =
      species.get(dexId)?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "#$dexId"

  fun item(id: Int): String = items.get(id)?.name ?: "item $id"
}

/**
 * The player's side of trading, plus the party, bag and money it needs to show. Fed from the netty
 * thread by [GameClient]; readers get the latest immutable values.
 */
class TradeClient(private val send: (Any) -> Unit, private val onChange: () -> Unit = {}) {
  @Volatile
  var state: TradeStatePacket = TradeStatePacket(TradePhase.CLOSED)
    private set

  @Volatile
  var party: List<Pokemon> = emptyList()
    private set

  @Volatile
  var pc: List<Pokemon> = emptyList()
    private set

  @Volatile
  var bag: Map<Int, Int> = emptyMap()
    private set

  @Volatile
  var money: Int? = null
    private set

  /** Every trade state change, for scripts that want to wait on one. */
  @Volatile
  var updates: Int = 0
    private set

  fun request(name: String) = send(TradeRequestPacket(name))

  fun accept() = action(TradeAction.ACCEPT)

  fun decline() = action(TradeAction.DECLINE)

  fun ready() = action(TradeAction.READY)

  fun confirm() = action(TradeAction.CONFIRM)

  fun cancel() = action(TradeAction.CANCEL)

  /** Puts the monster in party slot [slot] on the table, or takes it back off. */
  fun toggleMon(slot: Int) = send(TradeSelectMonPacket(slot))

  fun offerItem(itemId: Int, quantity: Int) =
      send(TradeOfferItemPacket(TradeOfferKind.ITEM, itemId, quantity))

  fun offerMoney(amount: Int) = send(TradeOfferItemPacket(TradeOfferKind.MONEY, 0, amount))

  /** A slash command, which the server only reads from the typed-chat packet. */
  fun command(text: String) = send(ChatMessageSendPacket(0, text, null))

  private fun action(code: Byte) = send(TradeActionPacket(code))

  internal fun onState(packet: TradeStatePacket) {
    state = packet
    updates++
    println("trade: ${describe(packet)}")
    onChange()
  }

  internal fun onContainer(packet: PokemonContainerPacket) {
    if (packet.delete) return
    when (packet.container) {
      PokemonContainer.PARTY -> party = packet.pokemon.sortedBy { it.containerSlot }
      PokemonContainer.PC -> pc = packet.pokemon.sortedBy { it.containerSlot }
      else -> return
    }
    onChange()
  }

  internal fun onSideParty(packet: BattleSidePartyPacket) {
    val stacks = packet.pokemon.filter { it.entityId and 0xFFFF == ITEM_ENTITY_TAG }
    if (!packet.replace || packet.side.toInt() != 1 || stacks.size != packet.pokemon.size) return
    bag = stacks.associate { it.frontSpriteId.toInt() to it.backSpriteId.toInt() }
    onChange()
  }

  internal fun onPlayerState(packet: LocalPlayerStatePacket) {
    money = packet.money
  }

  /** Money also changes through the market, which reports it as a currency balance. */
  internal fun onBalance(balance: Int) {
    money = balance
    onChange()
  }

  companion object {
    fun describe(p: TradeStatePacket): String = buildString {
      append("phase=${p.phase}")
      if (p.partnerName.isNotEmpty()) append(" partner=${p.partnerName}")
      if (p.phase == TradePhase.OPEN) {
        append(" mine=${offer(p.mine)} [${p.myStage}]")
        append(" theirs=${offer(p.theirs)} [${p.theirStage}]")
      }
      if (p.message.isNotEmpty()) append(" msg=\"${p.message}\"")
    }

    fun offer(o: TradeOfferView): String {
      val parts =
          o.mons.map { "${GameNames.species(it.dexId)} L${it.level} (slot ${it.partySlot})" } +
              o.items.map { "${it.quantity}x ${GameNames.item(it.itemId)}" } +
              listOfNotNull(o.money.takeIf { it > 0 }?.let { "$it money" })
      return if (parts.isEmpty()) "{}" else parts.joinToString(", ", "{", "}")
    }

    fun monLine(index: Int, mon: Pokemon): String =
        "$index: ${GameNames.species(mon.dexId)} L${mon.level} hp=${mon.hp} ot=${mon.ot} " +
            "id=${java.lang.Long.toHexString(mon.id)}"
  }

  init {
    log.debug { "trade client ready" }
  }
}
