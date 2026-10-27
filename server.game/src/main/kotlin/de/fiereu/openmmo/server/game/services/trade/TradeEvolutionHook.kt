package de.fiereu.openmmo.server.game.services.trade

import de.fiereu.openmmo.common.Pokemon

/**
 * Called once for every monster a completed trade handed to a new owner, after the swap is saved
 * (Gen 3: trade evolutions such as Kadabra -> Alakazam, or Seadra holding a Dragon Scale; see the
 * decomp's `GetEvolutionTargetSpecies(mon, EVO_MODE_TRADE, ...)` in `src/pokemon.c` and
 * `src/trade.c`). The evolution engine lives in the battle branch, which binds a real
 * implementation; the default does nothing.
 */
fun interface TradeEvolutionHook {
  suspend fun onTraded(receiverId: Long, monster: Pokemon)

  companion object {
    val NONE = TradeEvolutionHook { _, _ -> }
  }
}
