package de.fiereu.openmmo.server.game.services

import de.fiereu.network.SessionContext
import de.fiereu.openmmo.items.ItemDef
import de.fiereu.openmmo.server.game.session.PlayerState

/**
 * r5 merge: a key item used from the bag that starts a decomp script in the field (the Wailmer Pail
 * on the berry tree in front: BerryTree_EventScript_ItemUseWailmerPail). Lanes bind implementations
 * with `@IntoSet`; [tryUse] returns true when it took the use.
 */
interface BagItemScript {
  suspend fun tryUse(session: SessionContext, state: PlayerState, item: ItemDef): Boolean
}

@dagger.Module
interface BagItemScriptModule {
  @dagger.multibindings.Multibinds fun bagItemScripts(): Set<BagItemScript>
}
