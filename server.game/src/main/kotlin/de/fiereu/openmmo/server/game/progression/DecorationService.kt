package de.fiereu.openmmo.server.game.progression

import de.fiereu.openmmo.progression.Gen3Data
import de.fiereu.openmmo.server.game.storage.CharacterStore
import io.github.oshai.kotlinlogging.KotlinLogging
import javax.inject.Inject
import javax.inject.Singleton

private val log = KotlinLogging.logger {}

/** How many of one decoration a player may own, like the PC's decoration storage limits. */
private const val MAX_PER_DECORATION = 99
private const val MAX_BUY = 99

/**
 * Hoenn decoration marts (`pokemartdecoration` / `pokemartdecoration2`, decoration.c): what was
 * bought goes to the player's PC as decorations, kept as `openmmo/DECOR/<id>` counts. A secret base
 * to place them in is not built; the decorations can be bought and are stored.
 */
@Singleton
class DecorationService
@Inject
constructor(
    private val characters: CharacterStore,
    private val progress: PlayerProgressService,
) {
  /** Opens a decoration shop on the decomp stock list [label]. False if the label is unknown. */
  fun open(charId: Long, label: String, clerkEntityId: Long = -1): Boolean {
    val stock = Gen3Data.decorStocks[label] ?: return false
    progress.openDecorShop(charId, stock, clerkEntityId)
    progress.push(charId)
    return true
  }

  fun owned(charId: Long, decorId: Int): Int =
      characters.getCharacter(charId)?.storyVars?.get(ProgressKeys.decor(decorId)) ?: 0

  /** Buys [quantity] of [decorId] at the open shop. Null on success, else what to tell. */
  suspend fun buy(charId: Long, decorId: Int, quantity: Int): String? {
    val stock = progress.decorShop(charId) ?: return "No shop is open."
    if (decorId !in stock) return "That is not for sale here."
    val decor = Gen3Data.decorations[decorId] ?: return "That is not for sale here."
    if (quantity !in 1..MAX_BUY) return "Choose how many."
    val cost = decor.price.toLong() * quantity
    val have = owned(charId, decorId)
    if (have + quantity > MAX_PER_DECORATION) return "You can't store any more of those."
    val ok =
        characters.commitDurably(charId) { s ->
          if (s.info.money < cost) return@commitDurably null
          s.copy(
              info = s.info.copy(money = s.info.money - cost.toInt()),
              storyVars =
                  s.storyVars.toMutableMap().apply {
                    put(
                        ProgressKeys.decor(decorId),
                        (this[ProgressKeys.decor(decorId)] ?: 0) + quantity)
                  },
          )
        }
    if (!ok) return "You don't have enough money."
    log.info { "char=$charId bought $quantity ${decor.name} for $cost" }
    return null
  }
}
