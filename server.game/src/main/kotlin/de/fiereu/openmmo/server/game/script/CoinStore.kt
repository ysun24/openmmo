package de.fiereu.openmmo.server.game.script

import de.fiereu.network.SessionContext

/**
 * The coin case (r5 merge): the script interpreter's `checkcoins` / `addcoins` / `removecoins` /
 * `showcoinsbox` and the Game Corner (r5-minigames) read and write one store. r5-minigames binds it
 * to its `minigame_players.coins` column.
 */
interface CoinStore {
  suspend fun coins(charId: Long): Int

  /** AddCoins / RemoveCoins: false and no change when the result would leave 0..9999. */
  suspend fun addCoins(session: SessionContext?, charId: Long, delta: Int): Boolean
}
