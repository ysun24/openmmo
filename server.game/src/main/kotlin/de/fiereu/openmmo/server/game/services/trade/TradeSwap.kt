package de.fiereu.openmmo.server.game.services.trade

import de.fiereu.openmmo.common.MAX_PARTY_SIZE
import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.server.game.storage.StoredCharacter

/**
 * What one side puts on the table. Monsters are named by id, so a slot shuffle cannot swap them;
 * they may come from the party or the PC.
 */
data class TradeOffer(
    val monIds: List<Long> = emptyList(),
    val items: Map<Int, Int> = emptyMap(),
    val money: Int = 0,
) {
  val isEmpty: Boolean
    get() = monIds.isEmpty() && items.isEmpty() && money == 0
}

sealed interface SwapResult {
  data class Ok(val a: StoredCharacter, val b: StoredCharacter) : SwapResult

  data class Rejected(val reason: String) : SwapResult
}

/**
 * The swap itself, as a pure function of the two characters and their offers. It validates
 * everything again against the characters as they are now, since a lot can happen between an offer
 * and the final confirmation, and either returns both new aggregates or why it refused.
 */
object TradeSwap {
  const val MAX_OFFERED_MONS = MAX_PARTY_SIZE

  fun apply(
      a: StoredCharacter,
      offerA: TradeOffer,
      b: StoredCharacter,
      offerB: TradeOffer,
  ): SwapResult {
    if (a.info.id == b.info.id) return SwapResult.Rejected("You cannot trade with yourself.")
    if (offerA.isEmpty && offerB.isEmpty) return SwapResult.Rejected("Nothing is on the table.")
    validate(a, offerA)?.let {
      return SwapResult.Rejected(it)
    }
    validate(b, offerB)?.let {
      return SwapResult.Rejected(it)
    }

    val givenByA = offerA.monIds.map { id -> (a.pokemon + a.pcStorage).first { it.id == id } }
    val givenByB = offerB.monIds.map { id -> (b.pokemon + b.pcStorage).first { it.id == id } }

    val newA = receive(give(a, offerA), givenByB, offerB) ?: return overflow(a)
    val newB = receive(give(b, offerB), givenByA, offerA) ?: return overflow(b)

    if (newA.pokemon.isEmpty()) {
      return SwapResult.Rejected("${a.info.name} cannot trade away their last party monster.")
    }
    if (newB.pokemon.isEmpty()) {
      return SwapResult.Rejected("${b.info.name} cannot trade away their last party monster.")
    }
    return SwapResult.Ok(newA, newB)
  }

  /** Null when [offer] is something [owner] can actually give, otherwise the reason it is not. */
  fun validate(owner: StoredCharacter, offer: TradeOffer): String? {
    val name = owner.info.name
    if (offer.monIds.size > MAX_OFFERED_MONS) return "$name offered more than $MAX_OFFERED_MONS."
    if (offer.monIds.toSet().size != offer.monIds.size) return "$name offered a monster twice."
    for (id in offer.monIds) {
      if ((owner.pokemon + owner.pcStorage).none { it.id == id && it.ownerId == owner.info.id }) {
        return "$name no longer has an offered monster."
      }
    }
    for ((itemId, quantity) in offer.items) {
      if (quantity <= 0) return "$name offered a bad quantity."
      if ((owner.items[itemId] ?: 0) < quantity) return "$name does not hold $quantity of $itemId."
    }
    if (offer.money < 0) return "$name offered negative money."
    if (owner.info.money < offer.money) return "$name does not have ${offer.money} money."
    return null
  }

  private fun overflow(who: StoredCharacter): SwapResult =
      SwapResult.Rejected("${who.info.name} would hold more money or items than allowed.")

  /** [owner] without what it offered. The remaining party closes up its slots. */
  private fun give(owner: StoredCharacter, offer: TradeOffer): StoredCharacter {
    val gone = offer.monIds.toSet()
    val party =
        owner.pokemon
            .filter { it.id !in gone }
            .sortedBy { it.containerSlot }
            .mapIndexed { index, mon -> mon.copy(containerSlot = index.toShort()) }
    val pc = owner.pcStorage.filter { it.id !in gone }
    val items = owner.items.toMutableMap()
    for ((itemId, quantity) in offer.items) {
      val left = (items[itemId] ?: 0) - quantity
      if (left == 0) items.remove(itemId) else items[itemId] = left
    }
    return owner.copy(
        info = owner.info.copy(money = owner.info.money - offer.money),
        pokemon = party.toMutableList(),
        pcStorage = pc.toMutableList(),
        items = items,
    )
  }

  /**
   * [owner] with [mons] and the other side's items and money. Monsters fill the party first and
   * spill into the pc. Null when a counter would overflow.
   */
  private fun receive(
      owner: StoredCharacter,
      mons: List<Pokemon>,
      from: TradeOffer,
  ): StoredCharacter? {
    val party = owner.pokemon.toMutableList()
    val pc = owner.pcStorage.toMutableList()
    var nextPcSlot = (pc.maxOfOrNull { it.containerSlot.toInt() } ?: -1) + 1
    for (mon in mons) {
      val moved = mon.copy(ownerId = owner.info.id)
      if (party.size < MAX_PARTY_SIZE) {
        party +=
            moved.copy(container = PokemonContainer.PARTY, containerSlot = party.size.toShort())
      } else {
        pc += moved.copy(container = PokemonContainer.PC, containerSlot = nextPcSlot.toShort())
        nextPcSlot++
      }
    }
    val items = owner.items.toMutableMap()
    for ((itemId, quantity) in from.items) {
      val total = (items[itemId] ?: 0).toLong() + quantity
      if (total > Int.MAX_VALUE) return null
      items[itemId] = total.toInt()
    }
    val money = owner.info.money.toLong() + from.money
    if (money > Int.MAX_VALUE) return null
    return owner.copy(
        info = owner.info.copy(money = money.toInt()),
        pokemon = party,
        pcStorage = pc,
        items = items,
    )
  }
}
