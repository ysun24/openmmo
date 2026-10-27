package de.fiereu.openmmo.server.game.script.vm

import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.maps.MapManager
import de.fiereu.openmmo.moves.MoveRegistry
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import de.fiereu.openmmo.server.game.battle.WildMonFactory
import de.fiereu.openmmo.server.game.progression.HealLocations
import de.fiereu.openmmo.server.game.progression.ScriptProgress
import de.fiereu.openmmo.server.game.script.ScriptSpecials
import de.fiereu.openmmo.server.game.services.BattleService
import de.fiereu.openmmo.server.game.services.NpcService
import de.fiereu.openmmo.server.game.services.ScriptMovementService
import de.fiereu.openmmo.server.game.storage.CharacterStore
import de.fiereu.openmmo.trainer.TrainerRegistry
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Everything the decomp script interpreter reaches besides what ScriptContext already carries.
 * Providers break the construction cycles (the specials and the battle service reach back into the
 * script runner).
 */
@Singleton
class ScriptServices
@Inject
constructor(
    val ui: ScriptUiService,
    val npcs: NpcService,
    val maps: MapManager,
    val characters: CharacterStore,
    val items: ItemRegistry,
    val species: SpeciesRegistry,
    val moves: MoveRegistry,
    val trainers: TrainerRegistry,
    val movement: ScriptMovementService,
    val monFactory: WildMonFactory,
    private val specialsProvider: Provider<ScriptSpecials>,
    private val battlesProvider: Provider<BattleService>,
    private val healProvider: Provider<HealLocations>,
    private val progressProvider: Provider<ScriptProgress>,
    private val coinsProvider: Provider<de.fiereu.openmmo.server.game.script.CoinStore>,
) {
  /** r5 merge: the coin case (r5-minigames' store). */
  val coins: de.fiereu.openmmo.server.game.script.CoinStore
    get() = coinsProvider.get()

  val specials: ScriptSpecials
    get() = specialsProvider.get()

  val battles: BattleService
    get() = battlesProvider.get()

  val heal: HealLocations
    get() = healProvider.get()

  val progress: ScriptProgress
    get() = progressProvider.get()
}
