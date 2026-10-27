package de.fiereu.openmmo.server.game.services

import de.fiereu.network.PacketEvent
import de.fiereu.network.SessionContext
import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.PokemonMove
import de.fiereu.openmmo.common.enums.BattleAction
import de.fiereu.openmmo.common.enums.IVs
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.items.ItemDef
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.maps.MapManager
import de.fiereu.openmmo.moves.MoveRegistry
import de.fiereu.openmmo.net.game.packets.MapLoadedAckPacket
import de.fiereu.openmmo.net.game.packets.SocialListEntryAddPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleActionSelectPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleExtActionPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleExtKind
import de.fiereu.openmmo.net.game.packets.battle.BattleExtPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleListEventDetail
import de.fiereu.openmmo.net.game.packets.battle.BattleListEventPacket
import de.fiereu.openmmo.net.game.packets.battle.moves.MoveLearnPromptPacket
import de.fiereu.openmmo.net.game.packets.battle.moves.MoveLearnReplyPacket
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import de.fiereu.openmmo.server.game.battle.BattleChoice
import de.fiereu.openmmo.server.game.battle.BattleEvent
import de.fiereu.openmmo.server.game.battle.BattleInstance
import de.fiereu.openmmo.server.game.battle.BattleItemEffect
import de.fiereu.openmmo.server.game.battle.BattleItemEffects
import de.fiereu.openmmo.server.game.battle.BattleMonState
import de.fiereu.openmmo.server.game.battle.BattlePacketEmitter
import de.fiereu.openmmo.server.game.battle.BattleRegistry
import de.fiereu.openmmo.server.game.battle.BattleResult
import de.fiereu.openmmo.server.game.battle.BattleRewards
import de.fiereu.openmmo.server.game.battle.BattleRng
import de.fiereu.openmmo.server.game.battle.BattleRules
import de.fiereu.openmmo.server.game.battle.BattleTerrain
import de.fiereu.openmmo.server.game.battle.BattlerRef
import de.fiereu.openmmo.server.game.battle.CatchCalculator
import de.fiereu.openmmo.server.game.battle.CatchContext
import de.fiereu.openmmo.server.game.battle.FacilityBattleOutcome
import de.fiereu.openmmo.server.game.battle.FacilityBattleSetup
import de.fiereu.openmmo.server.game.battle.HeldItems
import de.fiereu.openmmo.server.game.battle.ItemNames
import de.fiereu.openmmo.server.game.battle.MoveLearnPrompts
import de.fiereu.openmmo.server.game.battle.MoveLearner
import de.fiereu.openmmo.server.game.battle.Shininess
import de.fiereu.openmmo.server.game.battle.StatCalculator
import de.fiereu.openmmo.server.game.battle.TurnEngine
import de.fiereu.openmmo.server.game.battle.WildMonFactory
import de.fiereu.openmmo.server.game.battle.acquiredMonsterDelta
import de.fiereu.openmmo.server.game.progression.Friendship
import de.fiereu.openmmo.server.game.progression.FriendshipEvent
import de.fiereu.openmmo.server.game.session.PLAYER_STATE
import de.fiereu.openmmo.server.game.storage.CharacterStore
import de.fiereu.openmmo.server.game.world.interest.InterestManager
import de.fiereu.openmmo.trainer.TrainerDef
import de.fiereu.openmmo.trainer.TrainerRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val log = KotlinLogging.logger {}

/** Gen 3 friendship on a level up (FRIENDSHIP_EVENT_GROW_LEVEL); see [Friendship]. */
internal fun friendshipAfterLevelUp(friendship: Int, sootheBell: Boolean = false): Int =
    Friendship.adjusted(friendship, FriendshipEvent.GROW_LEVEL, sootheBell, luxuryBall = false)

/**
 * Orchestrates battles: builds the battle state from the party and the opposing side, collects the
 * player's choices (one per battle position in a double battle), runs them through the [TurnEngine]
 * with the AI's, and persists the outcome (hp, pp, major status, held items, friendship). Packets
 * go out through the [BattlePacketEmitter] over the battle's interest key.
 */
@Singleton
class BattleService
@Inject
constructor(
    private val characterStore: CharacterStore,
    private val battles: BattleRegistry,
    private val engine: TurnEngine,
    private val wildMons: WildMonFactory,
    private val emitter: BattlePacketEmitter,
    private val rewards: BattleRewards,
    private val moveLearner: MoveLearner,
    private val interestManager: InterestManager,
    private val speciesRegistry: SpeciesRegistry,
    private val moveRegistry: MoveRegistry,
    private val trainers: TrainerRegistry,
    private val items: ItemRegistry,
    private val pvp: PvpBattleService,
    private val prompts: MoveLearnPrompts = MoveLearnPrompts(),
    private val evolution: EvolutionService? = null,
    private val maps: MapManager? = null,
    /** Lost wild and trainer battles white out (story battles and duels do not). */
    private val whiteOut:
        javax.inject.Provider<de.fiereu.openmmo.server.game.progression.WhiteOutService>? =
        null,
) {

  private val pokeBallItemId: Short by lazy { items.idOf(Items.POKE_BALL).toShort() }
  private val held = HeldItems(items)

  /** Party monsters that levelled up in the running battle, for the evolution check after it. */
  private val leveledIn = ConcurrentHashMap<Long, MutableSet<Long>>()

  init {
    emitter.itemNames = { id -> items.get(id)?.name ?: "item" }
    emitter.bagOf = { battle -> battleBag(battle) }
  }

  /**
   * The bag items usable in this battle, with what each needs a target for: "foe" (balls), "party"
   * (medicine, on any party monster), "active" (X items, the monster out) or "none". Balls are left
   * out of trainer battles and duels.
   */
  private fun battleBag(battle: BattleInstance): JsonArray {
    if (battle.pvp != null) return JsonArray(emptyList())
    if (battle.facilityBattle && battle.facility?.allowItems != true) return JsonArray(emptyList())
    val bag =
        if (battle.facilityBattle) battle.facility?.bag().orEmpty()
        else characterStore.getCharacter(battle.charId)?.items.orEmpty()
    return JsonArray(
        bag.entries
            .filter { it.value > 0 }
            .mapNotNull { (id, count) ->
              val def = items.get(id) ?: return@mapNotNull null
              val effect = BattleItemEffects.of(def) ?: return@mapNotNull null
              val target = BattleItemEffects.target(effect)
              if (target == "foe" && (battle.trainer != null || !battle.catchable))
                  return@mapNotNull null
              if (target == "none" && !battle.escapable) return@mapNotNull null
              buildJsonObject {
                put("id", id)
                put("name", def.name)
                put("count", count)
                put("target", target)
                put(
                    "pocket",
                    when (effect) {
                      BattleItemEffect.Ball -> "balls"
                      is BattleItemEffect.StatUp,
                      BattleItemEffect.DireHit,
                      BattleItemEffect.GuardSpec,
                      BattleItemEffect.Escape -> "battle"
                      else -> "medicine"
                    })
                if (effect is BattleItemEffect.RestorePp && !effect.allMoves) put("perMove", true)
              }
            }
            .sortedBy { it["pocket"].toString() })
  }

  fun onBattlePacket(event: PacketEvent<*>) {
    log.info { "Battle packet ${event.packet::class.simpleName} received: ${event.packet}" }
  }

  /** The retail-shaped action: always the first battle position that still owes a pick. */
  suspend fun onBattleAction(event: PacketEvent<BattleActionSelectPacket>) {
    val charId = event.session.attributes[PLAYER_STATE]?.characterId ?: return
    val battle = battles.byChar(charId) ?: return
    if (battle.pendingResult != null) return
    val action = event.packet
    log.info { "Battle action char=$charId: $action" }
    if (battle.pvp != null) {
      pvp.onAction(battle, action)
      return
    }
    val pos =
        multiPosition(battle, charId)
            ?: battle.owedSwitches.firstOrNull()
            ?: nextPosition(battle)
            ?: 0
    when (action.action) {
      BattleAction.MOVE -> choose(battle, BattleChoice.UseMove(0, pos, action.moveOrItemId.toInt()))
      BattleAction.ITEM ->
          useItem(battle, pos, action.moveOrItemId.toInt(), action.targetEntityId, -1)
      BattleAction.SWITCH -> switchMon(battle, pos, action.moveOrItemId.toInt())
      BattleAction.RUN -> flee(battle)
    }
  }

  /** Our own action packet: a position, an explicit target, items on party members. */
  suspend fun onExtAction(event: PacketEvent<BattleExtActionPacket>) {
    val charId = event.session.attributes[PLAYER_STATE]?.characterId ?: return
    val battle = battles.byChar(charId) ?: return
    if (battle.pendingResult != null) return
    val a = event.packet
    log.info { "Battle ext action char=$charId: $a" }
    if (battle.pvp != null) {
      pvp.onExtAction(battle, a)
      return
    }
    val pos = multiPosition(battle, charId) ?: a.position.coerceIn(0, battle.format - 1)
    when (a.kind) {
      BattleExtKind.MOVE -> {
        val target =
            if (a.targetSide in 0..1 && a.targetPos >= 0) BattlerRef(a.targetSide, a.targetPos)
            else null
        choose(battle, BattleChoice.UseMove(0, pos, a.id.toInt(), target))
      }
      BattleExtKind.ITEM -> useItem(battle, pos, a.id.toInt(), a.targetEntity, a.targetPos)
      BattleExtKind.SWITCH -> switchMon(battle, pos, a.id.toInt())
      BattleExtKind.RUN -> flee(battle)
    }
  }

  /** r5-frontier: in a multi battle each player acts for their own position only. */
  private fun multiPosition(battle: BattleInstance, charId: Long): Int? =
      if (battle.multiOwners.isEmpty()) null else if (charId == battle.charId) 0 else 1

  /** The next position (in order) with a live monster that has no choice yet. */
  private fun nextPosition(battle: BattleInstance): Int? =
      battle.own.actives.indices.firstOrNull { pos ->
        pos !in battle.pendingChoices &&
            pos !in (battle.facility?.aiPositions ?: emptySet()) &&
            battle.party.getOrNull(battle.own.actives[pos])?.fainted == false
      }

  /** Applies the moveset the player picked after a level up or an evolution. */
  fun onMoveLearnReply(event: PacketEvent<MoveLearnReplyPacket>) {
    val charId = event.session.attributes[PLAYER_STATE]?.characterId ?: return
    val reply = event.packet
    val pending = prompts.take(reply.entityId, charId) ?: return
    val stored =
        characterStore.getCharacter(charId)?.pokemon?.firstOrNull { it.id == reply.entityId }
            ?: return
    val moves = stored.moves.toMutableList()
    if (!moveLearner.apply(moves, reply.moveIds, pending.offered)) {
      log.warn { "char=$charId picked an invalid moveset for ${reply.entityId}: ${reply.moveIds}" }
      return
    }
    if (moves == stored.moves) return
    characterStore.updatePokemon(charId, stored.copy(moves = moves))
    characterStore.flushCharacterAsync(charId)
    // A battle still running holds its own copy, and the next reward writes that copy back over
    // the store. Move the live one across so the pick survives the rest of the battle.
    battles
        .byChar(charId)
        ?.party
        ?.firstOrNull { it.entityId == reply.entityId }
        ?.let { live ->
          live.moves.clear()
          live.moves.addAll(moves.map { PokemonMove(it.id, it.pp) })
          live.source = live.source.copy(moves = moves)
        }
    event.session.send(emitter.moveSlotsDelta(reply.entityId, moves.map { it.id to it.pp }, 0))
  }

  /** Throws a Poké Ball at the monster. False when the character is not in a battle. */
  suspend fun catchActiveWild(charId: Long): Boolean {
    val battle = battles.byChar(charId) ?: return false
    throwBall(battle, Items.POKE_BALL, pokeBallItemId.toInt(), consume = false)
    return true
  }

  /** Ends a running battle when the player disconnects, keeping the last hp, pp and status. */
  fun onDisconnect(session: SessionContext) {
    val charId = session.attributes[PLAYER_STATE]?.characterId ?: return
    prompts.forget(charId)
    leveledIn.remove(charId)
    pvp.onLogout(charId)
    evolution?.onLogout(charId)
    val battle = battles.byChar(charId) ?: return
    // A duel never writes its damage back, the rival wins by forfeit instead.
    if (battle.pvp != null) {
      pvp.onDisconnect(battle)
      return
    }
    // r5-frontier: leaving a facility battle loses it (the decomp's turned-off challenge).
    if (battle.facilityBattle) {
      finishBattle(battle, BattleResult.DISCONNECTED)
      return
    }
    persistParty(battle)
    finishBattle(battle, BattleResult.DISCONNECTED)
  }

  /** Resumes scripts after returning to the overworld. */
  fun onClientReady(event: PacketEvent<MapLoadedAckPacket>) {
    if (event.packet.data.isNotEmpty()) return
    val charId = event.session.attributes[PLAYER_STATE]?.characterId ?: return
    val battle = battles.byChar(charId) ?: return
    val result = battle.pendingResult ?: return
    // r5-frontier: a multi battle ends once both players are back on the map.
    if (battle.multiSessions.size > 1) {
      battle.multiAcked += charId
      if (!battle.multiAcked.containsAll(battle.multiSessions.keys)) return
    }
    finishBattle(battle, result)
  }

  /** True while the character has a battle running, so callers can skip starting another. */
  fun inBattle(charId: Long): Boolean = battles.byChar(charId) != null

  fun startWildBattle(
      session: SessionContext,
      dexId: Int,
      level: Int,
      terrain: String? = null,
      underwater: Boolean = false,
  ) {
    createBattle(
        session,
        listOf(OpponentSpec(dexId, level, emptyList(), wild = true)),
        catchable = true,
        escapable = true,
        terrain = terrain,
        underwater = underwater)
  }

  /** Runs a story battle and waits for its scene. */
  suspend fun startScriptedBattle(
      session: SessionContext,
      dexId: Int,
      level: Int,
      moveIds: List<Int> = emptyList(),
  ): BattleResult {
    val battle =
        createBattle(
            session,
            listOf(OpponentSpec(dexId, level, moveIds, wild = true)),
            catchable = false,
            escapable = false,
        ) ?: return BattleResult.FAILED
    return battle.completion.await()
  }

  /**
   * r5-scripts: setwildbattle + dowildbattle (legendaries, static encounters, gift battles): a
   * catchable wild battle the script waits on. A loss whites out like any wild battle.
   */
  suspend fun startScriptedWildBattle(
      session: SessionContext,
      dexId: Int,
      level: Int,
      heldItemName: String = "",
  ): BattleResult {
    val battle =
        createBattle(
            session,
            listOf(
                OpponentSpec(dexId, level, emptyList(), wild = true, heldItemName = heldItemName)),
            catchable = true,
            escapable = true,
        ) ?: return BattleResult.FAILED
    return battle.completion.await()
  }

  /** Runs a battle against the decomp trainer with this id and waits for its scene. */
  suspend fun startTrainerBattle(
      session: SessionContext,
      region: Region,
      trainerId: Int,
  ): BattleResult {
    val trainer = trainers.get(region, trainerId)
    if (trainer == null) {
      log.warn { "No $region trainer with id $trainerId" }
      return BattleResult.FAILED
    }
    return startTrainerBattle(session, trainer)
  }

  /** Runs a battle against a trainer's whole team and waits for its scene. */
  suspend fun startTrainerBattle(session: SessionContext, trainer: TrainerDef): BattleResult {
    val battle =
        createBattle(
            session,
            trainer.party.map {
              OpponentSpec(it.dexId, it.level, it.moveIds, it.iv, heldItemName = it.heldItemName)
            },
            catchable = false,
            escapable = false,
            trainer = trainer,
            // A double battle needs two monsters that can fight; with one it is fought single.
            format = if (trainer.doubleBattle) 2 else 1,
        ) ?: return BattleResult.FAILED
    return battle.completion.await()
  }

  /** Empty [moveIds] keeps the level up moveset, a null [iv] rolls one like a wild encounter. */
  private data class OpponentSpec(
      val dexId: Int,
      val level: Int,
      val moveIds: List<Int>,
      val iv: Int? = null,
      val wild: Boolean = false,
      val heldItemName: String = "",
  )

  /** The music the browser should play: gym / elite / champion / rival / trainer / wild. */
  private fun musicFor(trainer: TrainerDef?): String =
      when {
        trainer == null -> "wild"
        trainer.className in setOf("LEADER", "GYM_LEADER") -> "gym"
        trainer.className in setOf("ELITE_FOUR") -> "elite"
        trainer.className in setOf("CHAMPION") -> "champion"
        trainer.className in setOf("RIVAL", "RIVAL_EARLY", "RIVAL_LATE", "PKMN_TRAINER_3") ->
            "rival"
        trainer.className in
            setOf(
                "TEAM_AQUA",
                "TEAM_MAGMA",
                "AQUA_ADMIN",
                "MAGMA_ADMIN",
                "AQUA_LEADER",
                "MAGMA_LEADER",
                "TEAM_ROCKET",
                "BOSS",
                "ADMIN") -> "team"
        else -> "trainer"
      }

  /**
   * Gym leaders, the Elite Four and the champion fight on their own backgrounds (battle_bg.c picks
   * the leader / stadium palettes by trainer class), keyed like the exported backgrounds.
   */
  private fun trainerBackground(trainer: TrainerDef?): String? =
      when (trainer?.className) {
        null -> null
        "LEADER" -> "leader"
        "ELITE_FOUR" -> "elite_" + trainer.name.lowercase().filter { it.isLetter() }
        "CHAMPION" -> "champion"
        "TEAM_AQUA",
        "AQUA_ADMIN",
        "AQUA_LEADER" -> "team_aqua"
        "TEAM_MAGMA",
        "MAGMA_ADMIN",
        "MAGMA_LEADER" -> "team_magma"
        else -> null
      }

  /** The battle background for where the player stands (BattleSetup_GetEnvironmentId). */
  private fun terrainHere(session: SessionContext): String {
    val state = session.attributes[PLAYER_STATE] ?: return BattleTerrain.PLAIN
    val map = maps?.getMap(state.regionId, state.bankId, state.mapId)
    val tile = map?.tileAt(state.x.toInt(), state.y.toInt())
    return EncounterService.terrainFor(map, tile?.behavior)
  }

  private fun createBattle(
      session: SessionContext,
      opponents: List<OpponentSpec>,
      catchable: Boolean,
      escapable: Boolean,
      trainer: TrainerDef? = null,
      format: Int = 1,
      terrain: String? = null,
      underwater: Boolean = false,
  ): BattleInstance? {
    val charId = session.attributes[PLAYER_STATE]?.characterId ?: return null
    if (battles.byChar(charId) != null) {
      session.send(notice("You are already in a battle."))
      return null
    }
    val stored = characterStore.getCharacter(charId) ?: return null
    val partyMons =
        stored.pokemon
            .filter { it.container == PokemonContainer.PARTY }
            .sortedBy { it.containerSlot }
    if (partyMons.isEmpty()) {
      session.send(notice("You need a monster in your party to battle."))
      return null
    }
    val party = mutableListOf<BattleMonState>()
    for ((index, mon) in partyMons.withIndex()) {
      val def = speciesRegistry.get(mon.dexId)
      if (def == null) {
        session.send(notice("Your party has a species the battle data does not cover yet."))
        return null
      }
      party += BattleMonState(mon.id, def, index, mon, StatCalculator.computeAll(def, mon))
    }
    if (party.all { it.fainted || it.source.isEgg }) {
      session.send(notice("All of your monsters have fainted."))
      return null
    }
    val healthy = party.indices.filter { !party[it].fainted && !party[it].source.isEgg }
    val actualFormat = if (format >= 2 && healthy.size >= 2) 2 else 1
    val rng = BattleRng()
    val enemies = mutableListOf<BattleMonState>()
    for (spec in opponents) {
      var rolled = wildMons.create(spec.dexId, spec.level, rng)
      if (rolled == null) {
        session.send(notice("Unknown species ${spec.dexId}."))
        return null
      }
      // Gen 3: wild monsters roll against the player's OT id, trainer monsters are never shiny.
      if (trainer == null) {
        rolled = rolled.copy(isShiny = Shininess.isShiny(rolled.seed, Shininess.otId(charId)))
      }
      if (spec.moveIds.isNotEmpty()) {
        rolled =
            rolled.copy(
                moves =
                    spec.moveIds.take(4).map { id ->
                      PokemonMove(id.toShort(), (moveRegistry.get(id)?.pp ?: 0).toByte())
                    } + List((4 - spec.moveIds.size).coerceAtLeast(0)) { PokemonMove(0, 0) })
      }
      val def = speciesRegistry.get(spec.dexId)!!
      // A trainer's monsters are built to a fixed difficulty, so they must not keep the rolled
      // IVs. Max hp moves with them, and the monster comes out full.
      if (spec.iv != null) {
        val ivs =
            IVs().apply {
              hp = spec.iv
              atk = spec.iv
              this.def = spec.iv
              spAtk = spec.iv
              spDef = spec.iv
              spd = spec.iv
            }
        val fixed = rolled.copy(iVs = ivs)
        rolled = fixed.copy(hp = StatCalculator.computeAll(def, fixed).hp.toShort())
      }
      // Held items: the trainer's from its party table; a wild one's from the species
      // (itemCommon 50%, itemRare 5%, or always when both are the same item).
      val heldName =
          if (!spec.wild) spec.heldItemName else wildHeldItem(def.itemCommon, def.itemRare, rng)
      val heldId = heldName.takeIf { it.isNotEmpty() }?.let { ItemNames.wireId(items, it) } ?: 0
      rolled = rolled.copy(heldItem = heldId)
      enemies +=
          BattleMonState(rolled.id, def, null, rolled, StatCalculator.computeAll(def, rolled))
    }
    return launchBattle(
        session,
        charId,
        stored,
        party,
        healthy,
        actualFormat,
        enemies,
        rng,
        BattleRules(
            catchable,
            escapable,
            trainer,
            format = actualFormat,
            terrain = trainerBackground(trainer) ?: terrain ?: terrainHere(session),
            musicKey = musicFor(trainer),
            underwater = underwater))
  }

  /** The common tail of every battle start: registry, positions, interest, first packets. */
  private fun launchBattle(
      session: SessionContext,
      charId: Long,
      stored: de.fiereu.openmmo.server.game.storage.StoredCharacter,
      party: List<BattleMonState>,
      healthy: List<Int>,
      actualFormat: Int,
      enemies: List<BattleMonState>,
      rng: BattleRng,
      rules: BattleRules,
      configure: (BattleInstance) -> Unit = {},
      intro: String = "",
  ): BattleInstance {
    val trainer = rules.trainer
    log.info {
      "Starting ${if (actualFormat == 2) "double" else "single"} battle for char=$charId " +
          "(${stored.info.name}) against " +
          enemies.joinToString { "${it.species.name} level ${it.level}" }
    }
    val foeFormat = if (actualFormat == 2 && enemies.size >= 2) 2 else actualFormat
    // r3-items: the leads are seen as they come out (GetSetPokedexFlag FLAG_SET_SEEN).
    characterStore.registerSeen(
        charId, enemies.take(foeFormat.coerceAtLeast(1)).map { it.species.id })
    val battle = battles.create(charId, session, party, enemies, rng, rules)
    configure(battle)
    // Gen 3 obedience: traded monsters listen up to the badge level of the player's region.
    battle.trainerName = stored.info.name
    val region =
        if (session.attributes[PLAYER_STATE]?.regionId?.toInt() == Region.HOENN.wireValue.toInt())
            Region.HOENN
        else Region.KANTO
    battle.obedienceLevel =
        de.fiereu.openmmo.server.game.progression.BadgeService.obedienceLevel(stored, region)
    battle.own.actives.clear()
    battle.own.actives.addAll(healthy.take(actualFormat))
    battle.foe.actives.clear()
    battle.foe.actives.addAll((0 until foeFormat).toList())
    while (battle.foe.actives.size < actualFormat) battle.foe.actives.add(-1)
    battle.seenActive.clear()
    battle.seenActive.addAll(battle.own.actives)
    battle.opponentSeen.clear()
    battle.opponentSeen.addAll(battle.foe.actives.filter { it >= 0 })
    battle.participants.addAll(battle.own.actives)
    for (foe in battle.opponentMons().filterNotNull()) {
      foe.foughtBy += battle.activeMons().filterNotNull().map { it.entityId }
    }
    interestManager.join(session, battle.key)
    for ((cid, s) in battle.multiSessions) if (cid != charId) interestManager.join(s, battle.key)
    emitter.sendStart(battle, stored.info.name, prompt = false)
    if (intro.isNotBlank()) emitter.sendEvents(battle, listOf(BattleEvent.Message(intro)))
    emitter.sendEvents(battle, engine.startOfBattle(battle))
    if (battle.facility?.autoPlay != true) emitter.sendPrompt(battle)
    return battle
  }

  // --- r5-frontier: facility battles -----------------------------------------------------------

  /**
   * r5-frontier: a battle facility's battle (Battle Frontier, Battle Tents, Trainer Hill, Trainer
   * Tower). The player fights with [FacilityBattleSetup.team] (their own monsters at the facility's
   * level, or rentals) and nothing is written back; the end state comes back instead.
   */
  suspend fun startFacilityBattle(
      session: SessionContext,
      setup: FacilityBattleSetup,
      partner: Pair<SessionContext, FacilityBattleSetup>? = null,
  ): FacilityBattleOutcome {
    val charId =
        session.attributes[PLAYER_STATE]?.characterId
            ?: return FacilityBattleOutcome(BattleResult.FAILED, setup.team)
    val battle =
        createFacilityBattle(session, charId, setup, partner)
            ?: return FacilityBattleOutcome(BattleResult.FAILED, setup.team)
    if (battle.facility?.autoPlay == true) autoTurn(battle)
    val result = battle.completion.await()
    val byId = battle.party.associateBy { it.entityId }
    return FacilityBattleOutcome(result, setup.team.map { m -> byId[m.id]?.persisted() ?: m })
  }

  /** The partner's end state of a multi battle that [startFacilityBattle] ran for the leader. */
  fun multiPartnerTeam(battleOutcome: FacilityBattleOutcome, partnerTeam: List<Pokemon>) =
      partnerTeam.map { m -> battleOutcome.team.firstOrNull { it.id == m.id } ?: m }

  private fun createFacilityBattle(
      session: SessionContext,
      charId: Long,
      setup: FacilityBattleSetup,
      partner: Pair<SessionContext, FacilityBattleSetup>?,
  ): BattleInstance? {
    if (battles.byChar(charId) != null) {
      session.send(notice("You are already in a battle."))
      return null
    }
    val stored = characterStore.getCharacter(charId) ?: return null
    val partnerId = partner?.first?.attributes?.get(PLAYER_STATE)?.characterId
    if (partner != null && (partnerId == null || battles.byChar(partnerId) != null)) return null
    val team = setup.team + (partner?.second?.team ?: emptyList())
    val party = mutableListOf<BattleMonState>()
    for ((index, mon) in team.withIndex()) {
      val def = speciesRegistry.get(mon.dexId) ?: return null
      party += BattleMonState(mon.id, def, index, mon, StatCalculator.computeAll(def, mon))
    }
    val enemies =
        setup.opponents.map { mon ->
          val def = speciesRegistry.get(mon.dexId)!!
          BattleMonState(mon.id, def, null, mon, StatCalculator.computeAll(def, mon))
        }
    if (party.none { !it.fainted } || enemies.isEmpty()) return null
    val format = if (partner != null) 2 else setup.format
    val healthy =
        if (partner == null && setup.partnerFrom > 0) {
          listOf(
              (0 until setup.partnerFrom).first { !party[it].fainted },
              (setup.partnerFrom until party.size).first { !party[it].fainted })
        } else if (partner != null) {
          // Multi battle: position 0 is the leader's first monster, position 1 the partner's.
          listOf(
              setup.team.indices.first { !party[it].fainted },
              setup.team.size +
                  partner.second.team.indices.first { !party[setup.team.size + it].fainted })
        } else party.indices.filter { !party[it].fainted }
    val actualFormat = if (format >= 2 && healthy.size >= 2) 2 else 1
    val trainer =
        if (setup.wild) null
        else
            TrainerDef(
                // Not 0, which the client reads as a player duel; no decomp trainer has this id.
                id = Short.MAX_VALUE.toInt(),
                name = setup.trainerName,
                trainerClass = 0,
                doubleBattle = actualFormat == 2,
                prizeRate = 0,
                party = emptyList(),
                pic = setup.trainerPic,
                className = setup.trainerClass)
    val rules =
        BattleRules(
            catchable = false,
            escapable = false,
            trainer = trainer,
            format = actualFormat,
            terrain = setup.terrain,
            musicKey = if (setup.wild) "wild" else setup.musicKey)
    return launchBattle(
        session,
        charId,
        stored,
        party,
        healthy,
        actualFormat,
        enemies,
        BattleRng(),
        rules,
        configure = { b ->
          b.facility = setup.hooks
          b.facilityBattle = true
          if (partner == null && setup.partnerFrom > 0) {
            // An NPC partner: its slots belong to the "character" -1, played by the AI.
            b.multiOwners =
                party.indices.associateWith { if (it < setup.partnerFrom) charId else -1L }
          }
          if (partner != null && partnerId != null) {
            b.multiOwners =
                party.indices.associateWith { if (it < setup.team.size) charId else partnerId }
            b.multiSessions = mapOf(charId to session, partnerId to partner.first)
            battles.link(partnerId, b)
          }
        },
        intro = setup.intro)
  }

  /** Battle Palace: every position picks for itself, turn after turn, until the battle ends. */
  private suspend fun autoTurn(battle: BattleInstance) {
    if (battle.pendingResult != null || battles.byChar(battle.charId) !== battle) return
    // Paced like the decomp, one turn after the other, so the browser plays each turn.
    if (battle.turn > 1) kotlinx.coroutines.delay(AUTO_TURN_PACE_MS)
    if (battle.pendingResult != null || battles.byChar(battle.charId) !== battle) return
    resolveTurn(battle)
  }

  private companion object {
    const val AUTO_TURN_PACE_MS = 2500L
  }

  /** Who controls battle position [pos] of side 0 in a multi battle (null outside one). */
  private fun positionOwner(battle: BattleInstance, pos: Int): Long? {
    if (battle.multiOwners.isEmpty()) return null
    return if (pos == 0) battle.charId else battle.multiOwners.values.first { it != battle.charId }
  }

  private fun wildHeldItem(common: Int, rare: Int, rng: BattleRng): String {
    val c = ItemNames.byGbaId(common)
    val r = ItemNames.byGbaId(rare)
    val cName =
        if (common != 0)
            de.fiereu.openmmo.pokemon.generated.GeneratedGbaItemNames.names[common].orEmpty()
        else ""
    val rName =
        if (rare != 0)
            de.fiereu.openmmo.pokemon.generated.GeneratedGbaItemNames.names[rare].orEmpty()
        else ""
    if (c != null && common == rare) return cName
    val roll = rng.pick(100)
    return when {
      c != null && roll < 50 -> cName
      r != null && roll >= 95 -> rName
      else -> ""
    }
  }

  // --- choices and turns ---------------------------------------------------------------------

  private suspend fun choose(battle: BattleInstance, choice: BattleChoice) {
    if (battle.owedSwitches.isNotEmpty()) return
    // r5-frontier: in the Battle Palace the monsters choose for themselves.
    if (battle.facility?.autoPlay == true && choice !is BattleChoice.Pass) return
    val mon = battle.party.getOrNull(battle.own.actives.getOrNull(choice.pos) ?: -1)
    if (mon == null || mon.fainted) return
    if (choice is BattleChoice.UseMove) {
      val usable = mon.moves.filter { it.id.toInt() != 0 && it.pp > 0 }
      // An unknown or empty move falls back to the engine's Struggle check.
      if (usable.isNotEmpty() &&
          usable.none { it.id.toInt() == choice.moveId } &&
          mon.volatile.chargingMove == 0 &&
          mon.volatile.rampageMove == 0) {
        emitter.sendNotice(battle, "${mon.species.name} can't use that move.")
        emitter.sendPrompt(battle)
        return
      }
    }
    battle.pendingChoices[choice.pos] = choice
    if (nextPosition(battle) != null) {
      // Doubles: the other position still has to pick.
      emitter.sendState(battle)
      return
    }
    resolveTurn(battle)
  }

  private suspend fun resolveTurn(battle: BattleInstance) {
    val hooks = battle.facility
    hooks?.beforeTurn(engine, battle)
    var choices = battle.pendingChoices.values.toList()
    battle.pendingChoices.clear()
    // r5-frontier: Battle Palace picks for every position on both sides.
    val lead = mutableListOf<BattleEvent>()
    if (hooks != null) {
      val auto = mutableListOf<BattleChoice>()
      for (side in 0..1) {
        val actives = if (side == 0) battle.own.actives else battle.foe.actives
        for (pos in actives.indices) {
          val ref = BattlerRef(side, pos)
          val (c, line) = hooks.autoChoice(engine, battle, ref) ?: continue
          auto += c
          line?.let { lead += BattleEvent.Message(it) }
        }
      }
      // The AI plays an NPC partner's positions.
      for (pos in hooks.aiPositions) {
        val ref = BattlerRef(0, pos)
        val ctx = engine.context(battle)
        if (ctx.live(ref) != null && auto.none { it.side == 0 && it.pos == pos })
            auto +=
                de.fiereu.openmmo.server.game.battle.BattleAi.choose(ctx, ref, forceSmart = true)
      }
      if (auto.isNotEmpty()) {
        val keys = auto.map { it.side to it.pos }.toSet()
        choices = choices.filter { (it.side to it.pos) !in keys } + auto
      }
    }
    // Trainer items go before everything, like the player's; that monster spends its turn.
    val trainerPasses = trainerUsesItems(battle)
    val events = engine.resolve(battle, choices + trainerPasses, lead)
    emitter.sendEvents(battle, events)
    afterTurn(battle, events)
  }

  /** Gen 3 trainer AI items: a healing item when its active monster is below a quarter hp. */
  private fun trainerUsesItems(battle: BattleInstance): List<BattleChoice> {
    val trainer = battle.trainer ?: return emptyList()
    if (battle.trainerItems.isEmpty()) return emptyList()
    val passes = mutableListOf<BattleChoice>()
    for ((pos, mon) in battle.opponentMons().withIndex()) {
      if (mon == null || mon.fainted) continue
      val low = mon.currentHp * 4 < mon.maxHp
      val idx =
          battle.trainerItems.indexOfFirst { name ->
            when (val effect = ItemNames.byDecompName(name)?.let(BattleItemEffects::of)) {
              is BattleItemEffect.Heal -> low
              is BattleItemEffect.Cure -> mon.status and effect.bits != 0
              else -> false
            }
          }
      if (idx < 0) continue
      val def = ItemNames.byDecompName(battle.trainerItems.removeAt(idx)) ?: continue
      val effect = BattleItemEffects.of(def) ?: continue
      val (worked, events) =
          engine.useBagItem(battle, def.name, effect, mon, side = 1, user = trainer.name)
      emitter.sendEvents(battle, events)
      if (worked) passes += BattleChoice.Pass(1, pos)
    }
    return passes
  }

  private suspend fun afterTurn(battle: BattleInstance, events: List<BattleEvent>) {
    // r3-items: every foe that came out this turn is seen in the pokedex.
    battle
        .opponentMons()
        .filterNotNull()
        .map { it.species.id }
        .takeIf { it.isNotEmpty() }
        ?.let { characterStore.registerSeen(battle.charId, it) }
    events.filterIsInstance<BattleEvent.Fled>().firstOrNull()?.let {
      // Roar / Whirlwind / Teleport ended a wild battle.
      emitter.sendFled(battle)
      persistParty(battle)
      battle.pendingResult = BattleResult.FLED
      return
    }
    // r5-frontier: the facility's own end-of-turn rules (Arena judgment, Palace lines).
    battle.facility?.let { hooks ->
      val extra = hooks.afterTurn(engine, battle, events)
      if (extra.isNotEmpty()) emitter.sendEvents(battle, extra)
    }
    // Every opposing monster that fainted this turn pays its EXP now (facilities pay none).
    for (foe in battle.opponent) {
      if (foe.fainted && foe.entityId !in rewarded(battle)) {
        rewarded(battle) += foe.entityId
        if (!battle.facilityBattle) awardXp(battle, foe)
      }
    }
    val drew =
        battle.facility?.bothDownIsLoss == true &&
            battle.opponent.all { it.fainted } &&
            battle.party.all { it.fainted || it.source.isEgg }
    when {
      drew -> endDefeat(battle)
      battle.opponent.all { it.fainted } -> endVictory(battle)
      battle.party.all { it.fainted || it.source.isEgg } -> endDefeat(battle)
      else -> {
        // The opposing side fills its empty positions first.
        for (pos in battle.foe.actives.indices) {
          val current = battle.opponent.getOrNull(battle.foe.actives[pos])
          if (current != null && !current.fainted) continue
          val next =
              battle.opponent.indices.firstOrNull {
                it !in battle.foe.actives && !battle.opponent[it].fainted
              }
          if (next == null) {
            battle.foe.actives[pos] = -1
            continue
          }
          log.info { "Opponent sends out slot $next at $pos for char=${battle.charId}" }
          emitter.sendEvents(battle, engine.switchIn(battle, 1, pos, next))
          for (m in battle.opponentMons().filterNotNull()) {
            m.foughtBy +=
                battle.activeMons().filterNotNull().filter { !it.fainted }.map { it.entityId }
          }
        }
        // The player owes a replacement for every fainted position with a bench to draw from.
        battle.owedSwitches.clear()
        val bench =
            battle.party.indices.filter {
              it !in battle.own.actives &&
                  !battle.party[it].fainted &&
                  !battle.party[it].source.isEgg
            }
        var available = bench.size
        for (pos in battle.own.actives.indices) {
          val current = battle.party.getOrNull(battle.own.actives[pos])
          if (current != null && !current.fainted) continue
          if (available > 0) {
            battle.owedSwitches += pos
            available--
          } else {
            battle.own.actives[pos] = -1
          }
        }
        // r5-frontier: Arena / Palace monsters come out in party order by themselves; in a multi
        // battle each player only draws from their own monsters.
        if (battle.owedSwitches.isNotEmpty() &&
            (battle.facility?.allowSwitch == false || battle.multiOwners.isNotEmpty())) {
          for (pos in battle.owedSwitches.toList()) {
            val owner = positionOwner(battle, pos)
            val next =
                battle.party.indices.firstOrNull {
                  it !in battle.own.actives &&
                      !battle.party[it].fainted &&
                      !battle.party[it].source.isEgg &&
                      (owner == null || battle.multiOwners[it] == owner)
                }
            if (next == null) {
              battle.owedSwitches.remove(pos)
              battle.own.actives[pos] = -1
              continue
            }
            // A multi battle player brings two monsters, so the replacement is the only one left.
            if (battle.facility?.allowSwitch == false ||
                battle.multiOwners.isNotEmpty() ||
                pos in (battle.facility?.aiPositions ?: emptySet())) {
              battle.owedSwitches.remove(pos)
              emitter.sendEvents(battle, engine.switchIn(battle, 0, pos, next))
            }
          }
        }
        if (battle.owedSwitches.isNotEmpty()) {
          emitter.sendState(battle)
          emitter.sendSwitchPrompt(battle)
        } else {
          battle.turn += 1
          emitter.sendState(battle)
          if (battle.facility?.autoPlay == true) autoTurn(battle) else emitter.sendPrompt(battle)
        }
      }
    }
  }

  private val rewardedByBattle = ConcurrentHashMap<Long, MutableSet<Long>>()

  private fun rewarded(battle: BattleInstance): MutableSet<Long> =
      rewardedByBattle.getOrPut(battle.battleId) { mutableSetOf() }

  private suspend fun switchMon(battle: BattleInstance, pos: Int, target: Int) {
    val mon = battle.party.getOrNull(target)
    val forced = pos in battle.owedSwitches
    // r5-frontier: no switching by choice in the Arena / Palace; multi partners keep to their own.
    if (!forced && battle.facility?.allowSwitch == false) {
      emitter.sendNotice(battle, "You can't switch POKéMON here.")
      emitter.sendPrompt(battle)
      return
    }
    positionOwner(battle, pos)?.let { owner ->
      if (battle.multiOwners[target] != owner) {
        if (forced) emitter.sendSwitchPrompt(battle) else emitter.sendPrompt(battle)
        return
      }
    }
    val current = battle.party.getOrNull(battle.own.actives.getOrNull(pos) ?: -1)
    val invalid =
        mon == null ||
            mon.fainted ||
            mon.source.isEgg ||
            target in battle.own.actives ||
            battle.pendingChoices.values.any {
              it is BattleChoice.Switch && it.partyIndex == target
            }
    if (invalid) {
      // Reopen the switch screen on an invalid forced choice, otherwise re-prompt for an action.
      if (forced) emitter.sendSwitchPrompt(battle) else emitter.sendPrompt(battle)
      return
    }
    if (forced) {
      // A forced switch confirms the choice before the switch-in. Replacing a fainted mon does
      // not spend a turn; entry effects (Spikes, Intimidate) run at once.
      emitter.sendSwitchConfirm(battle)
      battle.owedSwitches.remove(pos)
      emitter.sendEvents(battle, engine.switchIn(battle, 0, pos, target))
      if (battle.party.all { it.fainted }) {
        endDefeat(battle)
        return
      }
      if (battle.owedSwitches.isNotEmpty()) {
        emitter.sendSwitchPrompt(battle)
      } else {
        battle.turn += 1
        emitter.sendState(battle)
        if (battle.facility?.autoPlay == true) autoTurn(battle) else emitter.sendPrompt(battle)
      }
      return
    }
    if (current == null || current.fainted) return
    engine.switchBlock(battle, current)?.let { reason ->
      emitter.sendNotice(battle, reason)
      emitter.sendPrompt(battle)
      return
    }
    choose(battle, BattleChoice.Switch(0, pos, target))
  }

  private suspend fun flee(battle: BattleInstance) {
    if (!battle.escapable) {
      emitter.sendNotice(
          battle,
          if (battle.trainer != null) "No! There's no running from a TRAINER battle!"
          else "You can't run from this battle.")
      emitter.sendPrompt(battle)
      return
    }
    val (escaped, events) = engine.tryEscape(battle)
    emitter.sendEvents(battle, events)
    if (escaped) {
      emitter.sendFled(battle)
      persistParty(battle)
      battle.pendingResult = BattleResult.FLED
      return
    }
    // A failed escape spends every position's turn.
    battle.pendingChoices.clear()
    for (pos in battle.own.actives.indices) battle.pendingChoices[pos] = BattleChoice.Pass(0, pos)
    resolveTurn(battle)
  }

  // --- bag -------------------------------------------------------------------------------------

  /**
   * A bag item from position [pos]: balls are thrown at once (a miss spends the turn), other items
   * are applied right away (items go before moves) and use up that position's turn.
   */
  private suspend fun useItem(
      battle: BattleInstance,
      pos: Int,
      itemId: Int,
      targetEntity: Long,
      moveSlot: Int
  ) {
    val def = items.get(itemId)
    val stored = characterStore.getCharacter(battle.charId)
    val refuse: (String) -> Unit = { text ->
      emitter.sendNotice(battle, text)
      emitter.sendPrompt(battle)
    }
    if (def == null || stored == null) return refuse("That item does not exist.")
    // r5-frontier: facility battles allow no bag items (the Pyramid brings its own bag).
    if (battle.facilityBattle && battle.facility?.allowItems != true)
        return refuse("Items can't be used here.")
    if (battle.facilityBattle) {
      val have = battle.facility?.bag()?.get(itemId) ?: 0
      if (have <= 0) return refuse("You don't have any ${def.name}.")
    }
    val effect = BattleItemEffects.of(def) ?: return refuse("${def.name} can't be used now.")
    if (!battle.facilityBattle && (stored.items[itemId] ?: 0) <= 0)
        return refuse("You don't have any ${def.name}.")
    when (effect) {
      BattleItemEffect.Ball -> {
        if (battle.trainer != null || battle.pvp != null || !battle.catchable) {
          emitter.sendEvents(
              battle,
              listOf(
                  BattleEvent.Message("The TRAINER blocked the BALL!"),
                  BattleEvent.Message("Don't be a thief!")))
          emitter.sendPrompt(battle)
          return
        }
        throwBall(battle, def, itemId, consume = true)
      }
      BattleItemEffect.Escape -> {
        if (!battle.escapable || battle.trainer != null) return refuse("It won't have any effect.")
        consume(battle, itemId)
        emitter.sendEvents(
            battle,
            listOf(
                BattleEvent.Message("You used ${def.name.uppercase()}!"),
                BattleEvent.Message("Got away safely!")))
        emitter.sendFled(battle)
        persistParty(battle)
        battle.pendingResult = BattleResult.FLED
      }
      else -> {
        val target =
            when (BattleItemEffects.target(effect)) {
              "active" -> battle.party.getOrNull(battle.own.actives.getOrNull(pos) ?: -1)
              else ->
                  battle.party.firstOrNull { it.entityId == targetEntity }
                      ?: battle.party.getOrNull(battle.own.actives.getOrNull(pos) ?: -1)
            }
        if (target == null) return refuse("Choose a POKéMON.")
        val (worked, events) = engine.useBagItem(battle, def.name, effect, target, moveSlot)
        if (!worked) return refuse("It won't have any effect.")
        consume(battle, itemId)
        emitter.sendEvents(battle, events)
        // A revived monster can be sent out again; a monster on the field keeps its spot.
        choose(battle, BattleChoice.Pass(0, pos))
      }
    }
  }

  private suspend fun consume(battle: BattleInstance, itemId: Int) {
    if (battle.facilityBattle) {
      battle.facility?.consume(itemId)
      return
    }
    if (!characterStore.addItem(battle.charId, itemId, -1)) return
    battle.session.send(
        itemStackUpdatePacket(
            itemId, characterStore.getCharacter(battle.charId)?.items?.get(itemId) ?: 0))
  }

  private suspend fun throwBall(
      battle: BattleInstance,
      ball: ItemDef,
      ballId: Int,
      consume: Boolean
  ) {
    if (!battle.catchable) {
      emitter.sendNotice(battle, "You can't catch this monster.")
      emitter.sendPrompt(battle)
      return
    }
    val targetMon = battle.opponentMons().firstOrNull { it != null && !it.fainted } ?: return
    if (consume) consume(battle, ballId)
    val stored = characterStore.getCharacter(battle.charId) ?: return
    val ctx =
        CatchContext(
            turn = battle.field.turnCounter,
            underwater = battle.underwater,
            alreadyCaught = stored.pokemon.any { it.dexId == targetMon.species.id })
    val result = CatchCalculator.roll(ball, targetMon, ctx, battle.rng)
    log.info { "char=${battle.charId} threw ${ball.name} at ${targetMon.species.name}: $result" }
    val ballEvent =
        BattleExtPacket(
            buildJsonObject {
                  put("t", "events")
                  put(
                      "e",
                      JsonArray(
                          listOf(
                              buildJsonObject {
                                put("k", "ball")
                                put("ball", ball.name)
                                put("shakes", result.shakes)
                                put("caught", result.caught)
                                put("id", targetMon.entityId)
                              })))
                }
                .toString())
    if (!result.caught) {
      emitter.broadcast(battle, ballEvent)
      val line =
          when (result.shakes) {
            0 -> "Oh, no! The POKéMON broke free!"
            1 -> "Aww! It appeared to be caught!"
            2 -> "Aargh! Almost had it!"
            else -> "Shoot! It was so close, too!"
          }
      emitter.sendEvents(battle, listOf(BattleEvent.Message(line)))
      // The throw spent the player's turn: the wild monster acts.
      battle.pendingChoices.clear()
      for (pos in battle.own.actives.indices) battle.pendingChoices[pos] = BattleChoice.Pass(0, pos)
      resolveTurn(battle)
      return
    }
    val party = stored.pokemon.filter { it.container == PokemonContainer.PARTY }
    val toBox = party.size >= 6
    val nextSlot =
        if (toBox)
            ((stored.pokemon
                    .filter { it.container == PokemonContainer.PC }
                    .maxOfOrNull { it.containerSlot } ?: -1) + 1)
                .toShort()
        else ((party.maxOfOrNull { it.containerSlot } ?: -1) + 1).toShort()
    val caught =
        targetMon
            .persisted()
            .copy(
                ownerId = battle.charId,
                container = if (toBox) PokemonContainer.PC else PokemonContainer.PARTY,
                containerSlot = nextSlot,
                ot = stored.info.name,
                caughtAt = LocalDateTime.now(),
                // Gen 3: a caught monster starts at its species' base friendship (Luxury Ball adds
                // no
                // starting bonus in Gen 3, only faster growth).
                friendship = targetMon.species.friendship,
            )
    log.info { "Caught wild ${targetMon.species.name} for char=${battle.charId}" }
    // The caught monster is sent as a full 148-byte record on opcode 0x14 before the ball-throw
    // event, so the client can resolve the monster when the throw lands.
    battle.session.send(SocialListEntryAddPacket(caught))
    battle.session.send(acquiredMonsterDelta(caught, targetMon.species))
    emitter.broadcast(battle, ballEvent)
    // "Player threw a Poke Ball" event.
    battle.session.send(
        BattleListEventPacket(
            kind = 0,
            value = ballId.toShort(),
            subKind = 4,
            detail = BattleListEventDetail(listType = 1, value = 1),
        ),
    )
    if (!characterStore.addPokemon(battle.charId, caught)) {
      log.error { "Could not persist the monster char=${battle.charId} just caught" }
    }
    if (toBox) emitter.sendNotice(battle, "${targetMon.species.name} was transferred to the PC.")
    endBattle(battle, BattleResult.CAUGHT)
  }

  // --- endings ---------------------------------------------------------------------------------

  private suspend fun endVictory(battle: BattleInstance) {
    var prize =
        if (battle.facilityBattle) 0
        else battle.trainer?.let { rewards.trainerPrize(it, battle.opponent.last().level) } ?: 0
    // Amulet Coin on any monster that fought doubles the prize.
    if (prize > 0 &&
        battle.party.any {
          held.of(it.heldItem)?.name == "DOUBLE_PRIZE" &&
              it.entityId in battle.opponent.flatMap { o -> o.foughtBy }
        })
        prize *= 2
    prize += battle.payDay
    val paid = prize > 0 && characterStore.addMoney(battle.charId, prize)
    if (prize > 0 && !paid) {
      log.error { "Could not pay char=${battle.charId} the $prize prize" }
    }
    endBattle(battle, BattleResult.VICTORY, if (paid) prize else 0)
  }

  /**
   * Pays EXP for knocking [defeated] out, the Gen 3 way (src/battle_script_commands.c Cmd_getexp):
   * split between the conscious party monsters that fought it, half of it to Exp. Share holders
   * when there are any, x1.5 from a trainer, x1.5 with a Lucky Egg, x1.5 for a traded monster.
   */
  private fun awardXp(battle: BattleInstance, defeated: BattleMonState) {
    val base =
        if (battle.trainer != null) rewards.trainerXp(defeated.species, defeated.level)
        else rewards.wildXp(defeated.species, defeated.level)
    val fighters =
        battle.party
            .filter { !it.fainted && it.entityId in defeated.foughtBy }
            .ifEmpty { battle.activeMons().filterNotNull().filter { !it.fainted } }
    val sharers = battle.party.filter { !it.fainted && held.of(it.heldItem)?.name == "EXP_SHARE" }
    val fighterPool = if (sharers.isEmpty()) base else base / 2
    val sharePool = if (sharers.isEmpty()) 0 else base / 2
    val playerName = characterStore.getCharacter(battle.charId)?.info?.name
    val payouts = LinkedHashMap<BattleMonState, Int>()
    for (f in fighters) payouts[f] =
        (payouts[f] ?: 0) + (fighterPool / fighters.size).coerceAtLeast(1)
    for (s in sharers) payouts[s] = (payouts[s] ?: 0) + (sharePool / sharers.size).coerceAtLeast(1)
    for ((winner, raw) in payouts) {
      var gained = raw
      if (held.of(winner.heldItem)?.name == "LUCKY_EGG") gained = gained * 150 / 100
      if (playerName != null && winner.source.ot.isNotEmpty() && winner.source.ot != playerName)
          gained = gained * 150 / 100
      giveXp(battle, winner, defeated, gained)
    }
  }

  private fun giveXp(
      battle: BattleInstance,
      winner: BattleMonState,
      defeated: BattleMonState,
      gained: Int
  ) {
    val reward = rewards.applyXp(winner, defeated.species, gained)
    log.info {
      "char=${battle.charId} ${winner.species.name}: +${reward.xpGained} xp, level ${winner.level} -> ${reward.newLevel}"
    }
    winner.currentHp = reward.newCurrentHp
    val outcome =
        moveLearner.learn(winner.moves, winner.source.dexId, winner.level, reward.newLevel)
    emitter.sendVictoryDelta(battle, winner.entityId, reward)
    for (move in outcome.learned) {
      emitter.sendNotice(battle, "${winner.species.name} learned ${move.name}!")
    }
    if (outcome.offered.isNotEmpty()) {
      val offered = outcome.offered.map { it.moveId.toShort() }
      prompts.offer(battle.charId, winner.entityId, offered)
      battle.session.send(MoveLearnPromptPacket(winner.entityId, offered))
    }
    var friendship = winner.source.friendship
    if (reward.leveled) {
      repeat(reward.newLevel - winner.level) { friendship = friendshipAfterLevelUp(friendship) }
      leveledIn.getOrPut(battle.charId) { mutableSetOf() } += winner.entityId
    }
    val grown =
        winner.source.copy(
            level = reward.newLevel.toByte(),
            xp = reward.newXp,
            hp = reward.newCurrentHp.toShort(),
            eVs = reward.newEvs,
            moves = winner.moves.map { PokemonMove(it.id, it.pp) },
            friendship = friendship,
        )
    winner.source = grown
    winner.stats = reward.newStats
    characterStore.updatePokemon(
        battle.charId, grown.copy(status = winner.status, heldItem = winner.heldItem))
  }

  private fun endDefeat(battle: BattleInstance) {
    // Gen 3 whites out: half the money is lost (simplified to the decomp's level-based loss).
    endBattle(battle, BattleResult.DEFEAT)
  }

  private fun endBattle(
      battle: BattleInstance,
      result: BattleResult,
      prizeMoney: Int = 0,
  ) {
    persistParty(battle)
    val party =
        characterStore
            .getCharacter(battle.charId)
            ?.pokemon
            ?.filter { it.container == PokemonContainer.PARTY }
            ?.sortedBy { it.containerSlot } ?: emptyList()
    emitter.sendBattleEnd(battle, party, prizeMoney)
    for ((cid, s) in battle.multiSessions) {
      if (cid == battle.charId) continue
      val theirs =
          characterStore
              .getCharacter(cid)
              ?.pokemon
              ?.filter { it.container == PokemonContainer.PARTY }
              ?.sortedBy { it.containerSlot } ?: emptyList()
      emitter.sendPartnerBattleEnd(s, cid, theirs)
    }
    battle.pendingResult = result
  }

  /** Write the battle's live hp, pp, status and held items back and flush the character. */
  private fun persistParty(battle: BattleInstance) {
    // r5-frontier: a facility owns its team copy, nothing goes back to the stored party.
    if (battle.facilityBattle) return
    for (state in battle.party) {
      if (state.ability == de.fiereu.openmmo.common.enums.Ability.NATURAL_CURE) state.status = 0
      var updated = state.persisted()
      // A faint costs a point of friendship.
      if (state.fainted && state.source.hp > 0)
          updated = updated.copy(friendship = (updated.friendship - 1).coerceAtLeast(0))
      characterStore.updatePokemon(battle.charId, updated)
    }
    characterStore.flushCharacterAsync(battle.charId)
  }

  private fun finishBattle(battle: BattleInstance, result: BattleResult) {
    interestManager.leave(battle.session, battle.key)
    battles.remove(battle.charId)
    for ((cid, s) in battle.multiSessions) {
      if (cid == battle.charId) continue
      interestManager.leave(s, battle.key)
      battles.remove(cid)
    }
    if (battle.facilityBattle) {
      rewardedByBattle.remove(battle.battleId)
      leveledIn.remove(battle.charId)
      battle.completion.complete(result)
      return
    }
    rewardedByBattle.remove(battle.battleId)
    val leveled = leveledIn.remove(battle.charId).orEmpty()
    battle.completion.complete(result)
    // FRIENDSHIP_EVENT_LEAGUE_BATTLE: beating a gym leader, the Elite Four or the champion.
    if (result == BattleResult.VICTORY &&
        battle.trainer?.className in setOf("LEADER", "GYM_LEADER", "ELITE_FOUR", "CHAMPION")) {
      characterStore
          .getCharacter(battle.charId)
          ?.pokemon
          ?.filter { it.container == PokemonContainer.PARTY }
          ?.forEach {
            characterStore.updatePokemon(
                battle.charId, Friendship.adjust(it, FriendshipEvent.LEAGUE_BATTLE, items))
          }
    }
    // Gen 3 white-out: a lost wild or trainer battle (not a story battle, not a duel).
    if (result == BattleResult.DEFEAT &&
        battle.pvp == null &&
        (battle.trainer != null || battle.catchable)) {
      whiteOut
          ?.get()
          ?.whiteOut(battle.session, battle.charId, againstTrainer = battle.trainer != null)
    }
    if (result != BattleResult.DISCONNECTED)
        evolution?.afterBattle(battle.session, battle.charId, leveled)
  }
}
