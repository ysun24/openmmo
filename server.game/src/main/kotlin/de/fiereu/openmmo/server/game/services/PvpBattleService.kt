package de.fiereu.openmmo.server.game.services

import de.fiereu.network.PacketEvent
import de.fiereu.network.SessionContext
import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.enums.BattleAction
import de.fiereu.openmmo.net.game.packets.DuelChallengePacket
import de.fiereu.openmmo.net.game.packets.DuelInviteOutcomePacket
import de.fiereu.openmmo.net.game.packets.DuelInvitePacket
import de.fiereu.openmmo.net.game.packets.InGameChallengeResponsePacket
import de.fiereu.openmmo.net.game.packets.battle.BattleActionSelectPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleTurnTimerPacket
import de.fiereu.openmmo.net.game.packets.battle.DuelRules
import de.fiereu.openmmo.net.game.packets.battle.DuelRulesChallengePacket
import de.fiereu.openmmo.net.game.packets.battle.DuelRulesPacket
import de.fiereu.openmmo.pokemon.SpeciesDef
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import de.fiereu.openmmo.server.game.battle.BattleInstance
import de.fiereu.openmmo.server.game.battle.BattleMonState
import de.fiereu.openmmo.server.game.battle.BattlePacketEmitter
import de.fiereu.openmmo.server.game.battle.BattleRegistry
import de.fiereu.openmmo.server.game.battle.BattleResult
import de.fiereu.openmmo.server.game.battle.BattleRng
import de.fiereu.openmmo.server.game.battle.BattleRules
import de.fiereu.openmmo.server.game.battle.DuelTimer
import de.fiereu.openmmo.server.game.battle.PvpDuel
import de.fiereu.openmmo.server.game.battle.StatCalculator
import de.fiereu.openmmo.server.game.battle.TurnEngine
import de.fiereu.openmmo.server.game.session.PLAYER_STATE
import de.fiereu.openmmo.server.game.session.SessionRegistry
import de.fiereu.openmmo.server.game.storage.CharacterStore
import de.fiereu.openmmo.server.game.world.interest.InterestManager
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock

private val log = KotlinLogging.logger {}

/** How long a challenge waits for its answer. */
private const val INVITE_TTL_MS = 60_000L

// The request type a duel invite carries. Our own client is the only reader so far.
private const val DUEL_REQUEST: Byte = 1

/** [DuelInviteOutcomePacket.packed] values, our own numbering. */
object DuelOutcome {
  const val DECLINED: Byte = 0
  const val ACCEPTED: Byte = 1
  const val UNAVAILABLE: Byte = 2
}

private data class Invite(
    val challengerId: Long,
    val challengerName: String,
    val at: Long,
    val rules: DuelRules = DuelRules.DEFAULT,
)

/** Keeps a client's rule request inside what the server supports. */
internal fun DuelRules.sanitized(): DuelRules =
    DuelRules(
        levelCap = if (levelCap <= 0) 0 else levelCap.coerceIn(1, 100),
        maxParty = if (maxParty <= 0) 6 else maxParty.coerceIn(1, 6),
        turnSeconds = if (turnSeconds <= 0) 0 else turnSeconds.coerceIn(5, 240),
    )

internal fun DuelRules.describe(): String =
    listOf(
            if (levelCap > 0) "Lv. cap $levelCap" else "no level cap",
            "$maxParty vs $maxParty",
            if (turnSeconds > 0) "${turnSeconds}s turns" else "no timer",
        )
        .joinToString(", ")

/**
 * Player-vs-player battles: the challenge and accept flow and the turns of a running duel. Each
 * side gets its own [BattleInstance] view so the existing [BattlePacketEmitter] can talk to both
 * clients unchanged. A duel gives no experience or prize, and the hp and pp it costs are never
 * written back, so both parties come out as they went in. The duel teams are copies built under the
 * duel's [DuelRules] (party size, level cap), and an optional turn timer acts for a player who
 * idles; after [DuelRules.MAX_TIMEOUTS] timeouts that player forfeits.
 */
@Singleton
class PvpBattleService
@Inject
constructor(
    private val characterStore: CharacterStore,
    private val sessions: SessionRegistry,
    private val battles: BattleRegistry,
    private val engine: TurnEngine,
    private val emitter: BattlePacketEmitter,
    private val interestManager: InterestManager,
    private val speciesRegistry: SpeciesRegistry,
    private val timer: DuelTimer,
) {
  /** Tests and callers that do not care about the clock. */
  constructor(
      characterStore: CharacterStore,
      sessions: SessionRegistry,
      battles: BattleRegistry,
      engine: TurnEngine,
      emitter: BattlePacketEmitter,
      interestManager: InterestManager,
      speciesRegistry: SpeciesRegistry,
  ) : this(
      characterStore,
      sessions,
      battles,
      engine,
      emitter,
      interestManager,
      speciesRegistry,
      DuelTimer())

  /** Open challenges by the challenged character's id. */
  private val invites = ConcurrentHashMap<Long, Invite>()

  /** The retail-shaped challenge: default rules. */
  fun onDuelChallenge(event: PacketEvent<DuelChallengePacket>) =
      challenge(event.session, event.packet.targetPlayerName, DuelRules.DEFAULT)

  /** Our own challenge packet, which carries the rules the challenger picked. */
  fun onRulesChallenge(event: PacketEvent<DuelRulesChallengePacket>) =
      challenge(event.session, event.packet.targetName, event.packet.rules.sanitized())

  private fun challenge(session: SessionContext, rawTarget: String, rules: DuelRules) {
    val charId = session.attributes[PLAYER_STATE]?.characterId ?: return
    val me = characterStore.getCharacter(charId) ?: return
    val targetName = rawTarget.trim()
    val targetId =
        sessions.onlineCharacterIds().firstOrNull {
          it != charId && characterStore.getCharacter(it)?.info?.name.equals(targetName, true)
        }
    val target = targetId?.let(sessions::getByCharacterId)
    if (targetId == null || target == null) {
      session.send(notice("$targetName is not online."))
      session.send(DuelInviteOutcomePacket(DuelOutcome.UNAVAILABLE))
      return
    }
    val busy =
        unavailableReason(charId, "You", rules) ?: unavailableReason(targetId, targetName, rules)
    if (busy != null) {
      session.send(notice(busy))
      session.send(DuelInviteOutcomePacket(DuelOutcome.UNAVAILABLE))
      return
    }
    invites[targetId] = Invite(charId, me.info.name, System.currentTimeMillis(), rules)
    log.info { "char=$charId (${me.info.name}) challenges char=$targetId ($targetName) $rules" }
    // The rules go first so the invite can show them.
    target.send(DuelRulesPacket(me.info.name, rules))
    target.send(DuelInvitePacket(flags = 0, requestType = DUEL_REQUEST, name = me.info.name))
    target.send(notice("${me.info.name} challenges you to a battle! (${rules.describe()})"))
    session.send(notice("You challenged $targetName. Waiting for an answer..."))
  }

  suspend fun onChallengeResponse(event: PacketEvent<InGameChallengeResponsePacket>) {
    val session = event.session
    val charId = session.attributes[PLAYER_STATE]?.characterId ?: return
    val invite = invites.remove(charId)
    if (invite == null || System.currentTimeMillis() - invite.at > INVITE_TTL_MS) {
      session.send(notice("There is no challenge to answer."))
      return
    }
    val named = event.packet.responseText.trim()
    if (named.isNotEmpty() && !named.equals(invite.challengerName, true)) {
      // An answer to a challenge that is no longer the open one.
      invites.putIfAbsent(charId, invite)
      session.send(notice("$named did not challenge you."))
      return
    }
    val challenger = sessions.getByCharacterId(invite.challengerId)
    if (challenger == null) {
      session.send(notice("${invite.challengerName} is no longer online."))
      return
    }
    val myName = characterStore.getCharacter(charId)?.info?.name ?: "?"
    if (!event.packet.accepted) {
      challenger.send(DuelInviteOutcomePacket(DuelOutcome.DECLINED))
      challenger.send(notice("$myName declined your challenge."))
      session.send(notice("You declined the challenge."))
      return
    }
    // Both parties are validated again under the duel's rules.
    val busy =
        unavailableReason(invite.challengerId, invite.challengerName, invite.rules)
            ?: unavailableReason(charId, "You", invite.rules)
    if (busy != null) {
      challenger.send(DuelInviteOutcomePacket(DuelOutcome.UNAVAILABLE))
      challenger.send(notice(busy))
      session.send(notice(busy))
      return
    }
    challenger.send(DuelInviteOutcomePacket(DuelOutcome.ACCEPTED))
    startDuel(challenger, session, invite.rules)
  }

  private fun unavailableReason(charId: Long, name: String, rules: DuelRules): String? {
    val stored = characterStore.getCharacter(charId) ?: return "$name cannot battle right now."
    val team = buildParty(stored.pokemon, rules)
    return when {
      battles.byChar(charId) != null -> "$name is already in a battle."
      stored.pokemon.isEmpty() -> "$name has no monster to battle with."
      team == null -> "$name has a species the battle data does not cover."
      team.all { it.fainted } -> "All of $name's monsters have fainted."
      else -> null
    }
  }

  /**
   * The duel team: the first [DuelRules.maxParty] monsters that can still fight, each scaled down
   * to the level cap. These are copies, the stored party is never touched.
   */
  internal fun buildParty(pokemon: List<Pokemon>, rules: DuelRules): List<BattleMonState>? {
    val healthy = pokemon.filter { it.hp > 0 }
    val team = healthy.ifEmpty { pokemon }.take(rules.maxParty.coerceIn(1, 6))
    return team.mapIndexed { index, mon ->
      val def = speciesRegistry.get(mon.dexId) ?: return null
      val capped =
          if (rules.levelCap > 0 && mon.level > rules.levelCap) scaled(def, mon, rules.levelCap)
          else mon
      BattleMonState(capped.id, def, index, capped, StatCalculator.computeAll(def, capped))
    }
  }

  /** The same monster at level [cap], with its hp kept at the same fraction. */
  private fun scaled(def: SpeciesDef, mon: Pokemon, cap: Int): Pokemon {
    val oldMax = StatCalculator.computeAll(def, mon).hp.coerceAtLeast(1)
    val lowered = mon.copy(level = cap.toByte())
    val newMax = StatCalculator.computeAll(def, lowered).hp
    val hp = if (mon.hp <= 0) 0 else (mon.hp * newMax / oldMax).coerceIn(1, newMax)
    return lowered.copy(hp = hp.toShort())
  }

  private fun startDuel(
      challengerSession: SessionContext,
      targetSession: SessionContext,
      rules: DuelRules,
  ) {
    val aId = challengerSession.attributes[PLAYER_STATE]?.characterId ?: return
    val bId = targetSession.attributes[PLAYER_STATE]?.characterId ?: return
    val aStored = characterStore.getCharacter(aId) ?: return
    val bStored = characterStore.getCharacter(bId) ?: return
    val aParty = buildParty(aStored.pokemon, rules) ?: return
    val bParty = buildParty(bStored.pokemon, rules) ?: return
    val battleRules = BattleRules(catchable = false, escapable = false, trainer = null)
    val a = battles.create(aId, challengerSession, aParty, bParty, BattleRng(), battleRules)
    val b = battles.create(bId, targetSession, bParty, aParty, BattleRng(), battleRules)
    val duel = PvpDuel(a, b, rules)
    a.pvp = duel
    b.pvp = duel
    for (side in duel.sides()) {
      val rival = duel.rival(side)
      side.activeSlot = side.party.indexOfFirst { !it.fainted }
      side.opponentSlot = rival.party.indexOfFirst { !it.fainted }
      side.seenActive.clear()
      side.seenActive.add(side.activeSlot)
      side.opponentSeen.clear()
      side.opponentSeen.add(side.opponentSlot)
    }
    log.info { "Starting duel ${aStored.info.name} vs ${bStored.info.name} $rules" }
    for ((side, name, rivalName) in
        listOf(
            Triple(a, aStored.info.name, bStored.info.name),
            Triple(b, bStored.info.name, aStored.info.name))) {
      interestManager.join(side.session, side.key)
      side.session.send(DuelRulesPacket(rivalName, rules))
      emitter.sendStart(side, name)
      emitter.sendNotice(side, "You are battling $rivalName! (${rules.describe()})")
    }
    armTimer(duel, duel.sides())
  }

  /** One player's pick. The turn resolves once both players have picked. */
  suspend fun onAction(battle: BattleInstance, action: BattleActionSelectPacket) {
    val duel = battle.pvp ?: return
    duel.mutex.withLock { act(duel, battle, action) }
  }

  /** Handles one pick with the duel's lock held. */
  private fun act(duel: PvpDuel, battle: BattleInstance, action: BattleActionSelectPacket) {
    if (duel.finished) return
    val rival = duel.rival(battle)
    when (action.action) {
      BattleAction.RUN -> {
        emitter.sendNotice(battle, "You forfeited the battle.")
        emitter.sendNotice(rival, "${nameOf(battle)} forfeited the battle.")
        end(duel, winner = rival)
        return
      }
      BattleAction.ITEM -> {
        emitter.sendNotice(battle, "Items can't be used in a battle against a player.")
        emitter.sendPrompt(battle)
        return
      }
      else -> Unit
    }
    if (battle.charId in duel.forced) {
      forcedSwitch(duel, battle, action)
      return
    }
    if (duel.forced.isNotEmpty() || battle.charId in duel.actions) return
    if (!valid(battle, action)) {
      emitter.sendNotice(battle, "You can't do that now.")
      emitter.sendPrompt(battle)
      return
    }
    duel.actions[battle.charId] = action
    val other = duel.actions[rival.charId]
    if (other == null) {
      emitter.sendNotice(battle, "Waiting for ${nameOf(rival)}...")
      return
    }
    duel.actions.clear()
    resolve(duel, battle, action, rival, other)
  }

  private fun valid(battle: BattleInstance, action: BattleActionSelectPacket): Boolean =
      when (action.action) {
        BattleAction.MOVE -> {
          val moves = battle.activeMon().moves
          val usable = moves.filter { it.id.toInt() != 0 && it.pp > 0 }
          // With no pp left anywhere the engine falls back on whatever was sent.
          usable.isEmpty() || usable.any { it.id == action.moveOrItemId }
        }
        BattleAction.SWITCH -> {
          val target = action.moveOrItemId.toInt()
          val mon = battle.party.getOrNull(target)
          mon != null && !mon.fainted && target != battle.activeSlot
        }
        else -> false
      }

  private fun resolve(
      duel: PvpDuel,
      x: BattleInstance,
      xAction: BattleActionSelectPacket,
      y: BattleInstance,
      yAction: BattleActionSelectPacket,
  ) {
    // Switches go first, the way they do in the games.
    if (xAction.action == BattleAction.SWITCH) switchSide(duel, x, xAction.moveOrItemId.toInt())
    if (yAction.action == BattleAction.SWITCH) switchSide(duel, y, yAction.moveOrItemId.toInt())
    val xMoves = xAction.action == BattleAction.MOVE
    val yMoves = yAction.action == BattleAction.MOVE
    val events =
        when {
          xMoves && yMoves -> engine.resolveTurn(x, xAction.moveOrItemId, yAction.moveOrItemId)
          // The switcher's view has the mover as its opponent, so the mover hits the new monster.
          xMoves -> engine.resolveSwitchTurn(y, xAction.moveOrItemId)
          yMoves -> engine.resolveSwitchTurn(x, yAction.moveOrItemId)
          else -> emptyList()
        }
    for (side in duel.sides()) emitter.sendEvents(side, events)
    afterTurn(duel)
  }

  private fun afterTurn(duel: PvpDuel) {
    val (a, b) = duel.first to duel.second
    when {
      a.party.all { it.fainted } -> end(duel, winner = b)
      b.party.all { it.fainted } -> end(duel, winner = a)
      else -> {
        for (side in duel.sides()) {
          if (side.activeMon().fainted) {
            duel.forced += side.charId
            emitter.sendSwitchPrompt(side)
            emitter.sendNotice(
                duel.rival(side), "Waiting for ${nameOf(side)} to send out a monster...")
          }
        }
        if (duel.forced.isEmpty()) promptBoth(duel)
        else armTimer(duel, duel.sides().filter { it.charId in duel.forced })
      }
    }
  }

  private fun forcedSwitch(
      duel: PvpDuel,
      battle: BattleInstance,
      action: BattleActionSelectPacket,
  ) {
    if (action.action != BattleAction.SWITCH || !valid(battle, action)) {
      emitter.sendSwitchPrompt(battle)
      return
    }
    emitter.sendSwitchConfirm(battle)
    switchSide(duel, battle, action.moveOrItemId.toInt())
    duel.forced -= battle.charId
    if (duel.forced.isEmpty()) promptBoth(duel)
  }

  private fun promptBoth(duel: PvpDuel) {
    for (side in duel.sides()) {
      side.turn += 1
      emitter.sendPrompt(side)
    }
    armTimer(duel, duel.sides())
  }

  // --- turn timer ----------------------------------------------------------------------------

  /** Starts the turn clock for the sides in [owing]. Call with the duel's lock held. */
  private fun armTimer(duel: PvpDuel, owing: List<BattleInstance>) {
    duel.cancelTimer()
    duel.cancelTimer = {}
    val seconds = duel.rules.turnSeconds
    if (seconds <= 0 || duel.finished) return
    val generation = ++duel.timerGeneration
    for (side in duel.sides()) {
      side.session.send(timerPacket(duel, side, seconds, mustAct = owing.any { it === side }))
    }
    duel.cancelTimer = timer.schedule(seconds * 1000L) { onTimeout(duel, generation) }
  }

  private fun timerPacket(
      duel: PvpDuel,
      side: BattleInstance,
      seconds: Int,
      mustAct: Boolean,
      autoPicked: Boolean = false,
  ) =
      BattleTurnTimerPacket(
          seconds = seconds,
          timeouts = duel.timeouts[side.charId] ?: 0,
          rivalTimeouts = duel.timeouts[duel.rival(side).charId] ?: 0,
          mustAct = mustAct,
          autoPicked = autoPicked,
      )

  /**
   * A turn clock ran out. Every side that still owes an action takes a timeout: at
   * [DuelRules.MAX_TIMEOUTS] it forfeits, otherwise the server acts for it (its first usable move,
   * or its first healthy monster when it owes a replacement).
   */
  internal fun onTimeout(duel: PvpDuel, generation: Int) {
    runBlocking {
      duel.mutex.withLock {
        if (duel.finished || generation != duel.timerGeneration) return@withLock
        val owing =
            if (duel.forced.isNotEmpty()) duel.sides().filter { it.charId in duel.forced }
            else duel.sides().filter { it.charId !in duel.actions }
        for (side in owing) {
          if (duel.finished) return@withLock
          val count = (duel.timeouts[side.charId] ?: 0) + 1
          duel.timeouts[side.charId] = count
          val rival = duel.rival(side)
          log.info { "Duel timeout char=${side.charId} ($count/${DuelRules.MAX_TIMEOUTS})" }
          if (count >= DuelRules.MAX_TIMEOUTS) {
            emitter.sendNotice(side, "You ran out of time too often and forfeit the battle.")
            emitter.sendNotice(rival, "${nameOf(side)} ran out of time and forfeits.")
            end(duel, winner = rival)
            return@withLock
          }
          emitter.sendNotice(
              side, "Time's up! The server picked for you. ($count/${DuelRules.MAX_TIMEOUTS})")
          emitter.sendNotice(rival, "${nameOf(side)} ran out of time.")
          side.session.send(timerPacket(duel, side, 0, mustAct = false, autoPicked = true))
          act(duel, side, autoAction(duel, side))
        }
      }
    }
  }

  private fun autoAction(duel: PvpDuel, side: BattleInstance): BattleActionSelectPacket {
    if (side.charId in duel.forced) {
      val slot = side.party.indexOfFirst { !it.fainted }
      return BattleActionSelectPacket(0, BattleAction.SWITCH, slot.toShort(), 0, 0)
    }
    val moves = side.activeMon().moves.filter { it.id.toInt() != 0 }
    val move = moves.firstOrNull { it.pp > 0 } ?: moves.firstOrNull()
    return BattleActionSelectPacket(0, BattleAction.MOVE, move?.id ?: 0, 0, 0)
  }

  // --- endings -------------------------------------------------------------------------------

  /** Brings [target] out for [side] and shows it to both players. */
  private fun switchSide(duel: PvpDuel, side: BattleInstance, target: Int) {
    val rival = duel.rival(side)
    val oldSlot = side.activeSlot
    val ownFull = target !in side.seenActive
    val rivalFull = target !in rival.opponentSeen
    side.activeSlot = target
    side.seenActive.add(target)
    rival.opponentSlot = target
    rival.opponentSeen.add(target)
    log.info { "Duel switch char=${side.charId} slot $oldSlot -> $target" }
    emitter.sendSwitchIn(side, oldSlot, ownFull)
    emitter.sendOpponentSwitchIn(rival, oldSlot, rivalFull)
  }

  private fun end(duel: PvpDuel, winner: BattleInstance) {
    if (duel.finished) return
    duel.finished = true
    duel.cancelTimer()
    val loser = duel.rival(winner)
    log.info { "Duel over: ${nameOf(winner)} beat ${nameOf(loser)}" }
    for (side in duel.sides()) {
      if (side.pendingResult != null) continue
      emitter.sendNotice(side, "${nameOf(winner)} won the battle!")
      // The stored party was never touched, so this also restores the client's hp and pp.
      val party = characterStore.getCharacter(side.charId)?.pokemon ?: emptyList()
      emitter.sendBattleEnd(side, party, 0)
      side.pendingResult = if (side === winner) BattleResult.VICTORY else BattleResult.DEFEAT
    }
  }

  /** The leaver forfeits. Their own view is closed at once, the rival's when its client is back. */
  fun onDisconnect(battle: BattleInstance) {
    val duel = battle.pvp ?: return
    // Runs on the closing channel's thread, outside any coroutine.
    runBlocking {
      duel.mutex.withLock {
        val rival = duel.rival(battle)
        battle.pendingResult = BattleResult.DISCONNECTED
        if (!duel.finished) {
          emitter.sendNotice(rival, "${nameOf(battle)} left the battle.")
          end(duel, winner = rival)
        }
        interestManager.leave(battle.session, battle.key)
        battles.remove(battle.charId)
        battle.completion.complete(BattleResult.DISCONNECTED)
      }
    }
    invites.values.removeIf { it.challengerId == battle.charId }
  }

  /** Drops challenges to or from a character that went offline. */
  fun onLogout(charId: Long) {
    invites.remove(charId)
    invites.values.removeIf { it.challengerId == charId }
  }

  private fun nameOf(battle: BattleInstance): String =
      characterStore.getCharacter(battle.charId)?.info?.name ?: "Your opponent"
}
