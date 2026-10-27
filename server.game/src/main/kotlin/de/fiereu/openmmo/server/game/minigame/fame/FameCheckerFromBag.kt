package de.fiereu.openmmo.server.game.minigame.fame

import de.fiereu.network.SessionContext
import de.fiereu.openmmo.items.ItemDef
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.services.BagItemScript
import de.fiereu.openmmo.server.game.session.PlayerState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FieldUseFunc_FameChecker (item_use.c): the FAME CHECKER used from the bag, or from SELECT once
 * registered (items.json: registrability 1), opens the Fame Checker. The key item is never used up.
 */
@Singleton
class FameCheckerFromBag @Inject constructor(private val fame: FameCheckerService) : BagItemScript {
  override suspend fun tryUse(session: SessionContext, state: PlayerState, item: ItemDef): Boolean {
    if (item != Items.FAME_CHECKER) return false
    val charId = state.characterId ?: return false
    fame.open(session, charId)
    return true
  }
}
