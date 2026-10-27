package de.fiereu.openmmo.server.game.testsupport

import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.maps.MapManager
import de.fiereu.openmmo.moves.MoveRegistry
import de.fiereu.openmmo.pokemon.EvolutionRegistry
import de.fiereu.openmmo.pokemon.LearnsetRegistry
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import de.fiereu.openmmo.server.game.battle.BattlePacketEmitter
import de.fiereu.openmmo.server.game.battle.BattleRegistry
import de.fiereu.openmmo.server.game.battle.BattleRewards
import de.fiereu.openmmo.server.game.battle.MoveLearnPrompts
import de.fiereu.openmmo.server.game.battle.MoveLearner
import de.fiereu.openmmo.server.game.battle.TurnEngine
import de.fiereu.openmmo.server.game.battle.WildMonFactory
import de.fiereu.openmmo.server.game.config.GameServerConfig
import de.fiereu.openmmo.server.game.progression.DaycareService
import de.fiereu.openmmo.server.game.progression.DecorationService
import de.fiereu.openmmo.server.game.progression.EscapeRopeService
import de.fiereu.openmmo.server.game.progression.MonGrowth
import de.fiereu.openmmo.server.game.progression.PlayerProgressService
import de.fiereu.openmmo.server.game.progression.ProgressActionService
import de.fiereu.openmmo.server.game.progression.ScriptProgress
import de.fiereu.openmmo.server.game.script.ScriptRegistry
import de.fiereu.openmmo.server.game.script.ScriptRunner
import de.fiereu.openmmo.server.game.services.BattleService
import de.fiereu.openmmo.server.game.services.DialogService
import de.fiereu.openmmo.server.game.services.EncounterService
import de.fiereu.openmmo.server.game.services.EvolutionService
import de.fiereu.openmmo.server.game.services.FieldItemService
import de.fiereu.openmmo.server.game.services.MapEntryScripts
import de.fiereu.openmmo.server.game.services.MapLoadService
import de.fiereu.openmmo.server.game.services.MapScriptService
import de.fiereu.openmmo.server.game.services.MovementService
import de.fiereu.openmmo.server.game.services.NpcService
import de.fiereu.openmmo.server.game.services.PresenceService
import de.fiereu.openmmo.server.game.services.PvpBattleService
import de.fiereu.openmmo.server.game.services.ScriptMovementService
import de.fiereu.openmmo.server.game.services.ScriptWarpService
import de.fiereu.openmmo.server.game.services.ShopService
import de.fiereu.openmmo.server.game.services.StoryPlayerService
import de.fiereu.openmmo.server.game.services.StoryService
import de.fiereu.openmmo.server.game.services.WarpService
import de.fiereu.openmmo.server.game.session.SessionRegistry
import de.fiereu.openmmo.server.game.storage.CharacterStore
import de.fiereu.openmmo.server.game.storage.EntityIdService
import de.fiereu.openmmo.server.game.world.interest.InterestManager
import de.fiereu.openmmo.server.game.world.interest.PassThroughInterestPolicy
import de.fiereu.openmmo.trainer.TrainerRegistry
import de.fiereu.openmmo.typechart.TypeChart

/**
 * A real [MovementService] with every collaborator built for real, since none of them are worth
 * faking. Pass a [mapManager] that already holds the maps the test walks on.
 */
fun movementService(
    store: CharacterStore,
    mapManager: MapManager = MapManager(),
): MovementService {
  val mapLoad = MapLoadService(mapManager)
  val interest = InterestManager()
  val presence = PresenceService(interest, PassThroughInterestPolicy(), mapLoad, store)
  val npcs = NpcService(mapManager, store)
  val story = StoryService(store)
  val battles = battleService(store, interest)
  val entryScripts = MapEntryScripts(ScriptRegistry(emptyMap()), story)
  val runner = scriptRunner(store, mapManager, interest, battles)
  val progress by lazy { progressActions(store, mapManager, runner, battles) }
  return MovementService(
      WarpService(mapLoad, mapManager, store, presence),
      mapLoad,
      npcs,
      presence,
      mapManager,
      store,
      EncounterService(store, battles),
      MapScriptService(entryScripts, runner),
      { progress },
  )
}

/**
 * A real [ScriptRunner]. It launches on whatever scope the session carries under SCRIPT_SCOPE, so
 * put the test's own scope there to be able to step a script.
 */
fun scriptRunner(
    store: CharacterStore,
    mapManager: MapManager = MapManager(),
    interest: InterestManager = InterestManager(),
    battles: BattleService = battleService(store, interest),
): ScriptRunner {
  val mapLoad = MapLoadService(mapManager)
  val presence = PresenceService(interest, PassThroughInterestPolicy(), mapLoad, store)
  val npcs = NpcService(mapManager, store)
  val story = StoryService(store)
  val species = SpeciesRegistry()
  val moves = MoveRegistry()
  val wildMons = WildMonFactory(species, moves, LearnsetRegistry(), EntityIdService())
  val items = ItemRegistry()
  return ScriptRunner(
      DialogService(),
      story,
      ScriptMovementService(mapManager, npcs, store),
      ScriptWarpService(mapManager, mapLoad, store, presence),
      StoryPlayerService(store, wildMons, species, moves, items),
      battles,
      store,
      mapManager,
      MapEntryScripts(ScriptRegistry(emptyMap()), story),
      ShopService(store, items),
      scriptProgress(store),
  )
}

/** The r3-items pieces, built for real over [store]. */
fun progressService(store: CharacterStore) =
    PlayerProgressService(store, SessionRegistry(), SpeciesRegistry())

fun daycareService(
    store: CharacterStore,
    progress: PlayerProgressService = progressService(store)
) =
    DaycareService(
        store,
        SpeciesRegistry(),
        LearnsetRegistry(),
        MoveRegistry(),
        WildMonFactory(SpeciesRegistry(), MoveRegistry(), LearnsetRegistry(), EntityIdService()),
        progress)

fun scriptProgress(
    store: CharacterStore,
    progress: PlayerProgressService = progressService(store),
) =
    ScriptProgress(
        store, progress, daycareService(store, progress), DecorationService(store, progress))

fun escapeRope(store: CharacterStore, mapManager: MapManager = MapManager()): EscapeRopeService {
  val mapLoad = MapLoadService(mapManager)
  val presence = PresenceService(InterestManager(), PassThroughInterestPolicy(), mapLoad, store)
  return EscapeRopeService(
      store, mapManager, ScriptWarpService(mapManager, mapLoad, store, presence))
}

fun fieldItemService(
    store: CharacterStore,
    battles: BattleService = battleService(store),
    progress: PlayerProgressService = progressService(store),
    mapManager: MapManager = MapManager(),
    evolution: EvolutionService = evolutionService(store),
): FieldItemService {
  val species = SpeciesRegistry()
  val moves = MoveRegistry()
  return FieldItemService(
      store,
      ItemRegistry(),
      species,
      moves,
      battles,
      MonGrowth(species, MoveLearner(LearnsetRegistry(), moves)),
      progress,
      EntityIdService(),
      escapeRope(store, mapManager),
      evolution)
}

/** The one evolution engine, over [store]. */
fun evolutionService(store: CharacterStore) =
    EvolutionService(
        store,
        SpeciesRegistry(),
        EvolutionRegistry(),
        ItemRegistry(),
        MoveLearner(LearnsetRegistry(), MoveRegistry()),
        MoveLearnPrompts(),
        SessionRegistry(),
        EntityIdService())

fun progressActions(
    store: CharacterStore,
    mapManager: MapManager,
    runner: ScriptRunner,
    battles: BattleService,
): ProgressActionService {
  val progress = progressService(store)
  val daycare = daycareService(store, progress)
  val decorations = DecorationService(store, progress)
  return ProgressActionService(
      store,
      ItemRegistry(),
      fieldItemService(store, battles, progress, mapManager),
      progress,
      ScriptProgress(store, progress, daycare, decorations),
      decorations,
      daycare,
      escapeRope(store, mapManager),
      runner,
      GameServerConfig(
          host = "127.0.0.1",
          port = 0,
          checksumSize = 2,
          rootKeyResource = "",
          sessionSecret = ByteArray(1)),
  )
}

fun battleService(
    store: CharacterStore,
    interest: InterestManager = InterestManager(),
): BattleService {
  val species = SpeciesRegistry()
  val moves = MoveRegistry()
  return BattleService(
      characterStore = store,
      battles = BattleRegistry(),
      engine = TurnEngine(moves, TypeChart()),
      wildMons = WildMonFactory(species, moves, LearnsetRegistry(), EntityIdService()),
      emitter = BattlePacketEmitter(interest),
      rewards = BattleRewards(),
      moveLearner = MoveLearner(LearnsetRegistry(), moves),
      interestManager = interest,
      speciesRegistry = species,
      moveRegistry = moves,
      trainers = TrainerRegistry(),
      items = ItemRegistry(),
      pvp =
          PvpBattleService(
              store,
              SessionRegistry(),
              BattleRegistry(),
              TurnEngine(moves, TypeChart()),
              BattlePacketEmitter(interest),
              interest,
              species),
  )
}
