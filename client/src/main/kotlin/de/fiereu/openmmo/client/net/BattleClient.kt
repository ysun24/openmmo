package de.fiereu.openmmo.client.net

import de.fiereu.network.PacketEvent
import de.fiereu.openmmo.common.enums.BattleAction
import de.fiereu.openmmo.common.enums.GrowthRate
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.moves.MoveRegistry
import de.fiereu.openmmo.net.game.packets.DuelChallengePacket
import de.fiereu.openmmo.net.game.packets.DuelInviteOutcomePacket
import de.fiereu.openmmo.net.game.packets.DuelInvitePacket
import de.fiereu.openmmo.net.game.packets.EntityMovePpPacket
import de.fiereu.openmmo.net.game.packets.InGameChallengeResponsePacket
import de.fiereu.openmmo.net.game.packets.MapLoadedAckPacket
import de.fiereu.openmmo.net.game.packets.PokemonContainerPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleActionSelectPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleBulkStatePacket
import de.fiereu.openmmo.net.game.packets.battle.BattleEntityDeltaPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleEntityMoveEventPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleEventBody
import de.fiereu.openmmo.net.game.packets.battle.BattleFieldStatePacket
import de.fiereu.openmmo.net.game.packets.battle.BattleListEventPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleQueuedEventPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleSlotEventEnumPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleSlotFlagEventPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleStatCountersPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleSwitchInPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleTurnTimerPacket
import de.fiereu.openmmo.net.game.packets.battle.DuelRules
import de.fiereu.openmmo.net.game.packets.battle.DuelRulesChallengePacket
import de.fiereu.openmmo.net.game.packets.battle.DuelRulesPacket
import de.fiereu.openmmo.net.game.packets.battle.OpposingSide
import de.fiereu.openmmo.net.game.packets.battle.moves.MoveLearnPromptPacket
import de.fiereu.openmmo.net.game.packets.battle.moves.MoveLearnReplyPacket
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

// Bits of the per-target outcome word, mirroring the server's emitter.
private const val OUTCOME_MISSED = 1
private const val OUTCOME_FAILED = 4
private const val SUPER_EFFECTIVE_BIT = 0x20
private const val NOT_VERY_EFFECTIVE_BIT = 0x10
private const val BATTLE_END_FLAG: Byte = 2
private const val FLED_PHASE: Byte = -1
private const val FX_KEPT = 120
private const val BALL_SHAKES = 3

/** Names and max pp from the codegen tables the server also uses. */
object BattleNames {
  private val moves by lazy { MoveRegistry() }
  private val species by lazy { SpeciesRegistry() }

  fun move(id: Int): String = moves.get(id)?.name ?: "MOVE $id"

  fun maxPp(id: Int): Int = moves.get(id)?.pp ?: 0

  /** The move's type name (NORMAL, FIRE, ...), or null for an unknown move. */
  fun moveType(id: Int): String? =
      moves.get(id)?.type?.name?.takeUnless { it.startsWith("QUESTION") || it == "NONE" }

  fun power(id: Int): Int = moves.get(id)?.power ?: 0

  fun accuracy(id: Int): Int = moves.get(id)?.accuracy ?: 0

  fun growth(species: Int): GrowthRate? = this.species.get(species)?.growthRate

  /** Total experience to reach [level] on the Gen 3 curve, as the server's ExpCurves. */
  fun totalXpFor(rate: GrowthRate, level: Int): Int {
    if (level <= 1) return level.coerceAtLeast(0)
    val n = level.coerceAtMost(100)
    val cube = n * n * n
    return when (rate) {
      GrowthRate.MEDIUM_FAST -> cube
      GrowthRate.SLOW -> 5 * cube / 4
      GrowthRate.FAST -> 4 * cube / 5
      GrowthRate.MEDIUM_SLOW -> 6 * cube / 5 - 15 * n * n + 100 * n - 140
      GrowthRate.ERRATIC ->
          when {
            n <= 50 -> (100 - n) * cube / 50
            n <= 68 -> (150 - n) * cube / 100
            n <= 98 -> ((1911 - 10 * n) / 3) * cube / 500
            else -> (160 - n) * cube / 100
          }
      GrowthRate.FLUCTUATING ->
          when {
            n <= 15 -> ((n + 1) / 3 + 24) * cube / 50
            n <= 36 -> (n + 14) * cube / 50
            else -> (n / 2 + 32) * cube / 50
          }
    }
  }

  /** How far [xp] is between [level] and the next level, 0..1. Null when unknown. */
  fun expFraction(species: Int, level: Int, xp: Int?): Double? {
    if (xp == null) return null
    val rate = growth(species) ?: return null
    if (level >= 100) return 1.0
    val lo = totalXpFor(rate, level)
    val hi = totalXpFor(rate, level + 1)
    if (hi <= lo) return 0.0
    return ((xp - lo).toDouble() / (hi - lo)).coerceIn(0.0, 1.0)
  }

  fun species(id: Int): String = species.get(id)?.name ?: "#$id"

  private val trainers by lazy { de.fiereu.openmmo.trainer.TrainerRegistry() }

  fun trainer(regionId: Int, id: Int): String? {
    val region =
        de.fiereu.openmmo.common.enums.Region.entries.firstOrNull {
          it.wireValue.toInt() == regionId
        }
    return region?.let { trainers.get(it, id)?.name?.takeIf(String::isNotBlank) }
  }

  val pokeBall: Short by lazy { ItemRegistry().idOf(Items.POKE_BALL).toShort() }

  private val statNames =
      mapOf(
          1 to "ATTACK",
          2 to "DEFENSE",
          3 to "SPEED",
          4 to "SP. ATK",
          5 to "SP. DEF",
          6 to "accuracy",
          7 to "evasiveness")

  fun stat(id: Int): String = statNames[id] ?: "stat $id"
}

data class BattleMove(val id: Int, val pp: Int, val maxPp: Int) {
  val name: String
    get() = BattleNames.move(id)
}

data class BattleMon(
    val slot: Int,
    val entityId: Long,
    val species: Int,
    val level: Int,
    val hp: Int,
    val maxHp: Int,
    val moves: List<BattleMove> = emptyList(),
    /** Opposing monsters not shown yet are hidden stubs. */
    val revealed: Boolean = true,
    /** Total experience points, when the server told us (own monsters only). */
    val xp: Int? = null,
) {
  val name: String
    get() = BattleNames.species(species)

  val fainted: Boolean
    get() = revealed && hp <= 0
}

enum class BattlePhase {
  /** Waiting for the server, between prompts. */
  WAITING,
  /** The server wants a FIGHT/BAG/POKEMON/RUN action. */
  ACTION,
  /** The active monster fainted, a replacement must be picked. */
  FORCED_SWITCH,
  ENDED,
}

/**
 * One step of what happened, in order, for a presentation layer to animate. [seq] grows by one per
 * effect over the client's lifetime. [side] is "me" or "foe" where it applies. Kinds: start, text,
 * move, hp, miss, stat, faint, switch, ball, exp, level, end.
 */
data class BattleFx(
    val seq: Int,
    val kind: String,
    val side: String? = null,
    val data: Map<String, Any?> = emptyMap(),
)

/** A running duel turn clock as the server last announced it. */
data class TurnTimer(
    /** Grows with every new clock, so a presentation layer can restart its countdown. */
    val id: Int,
    val seconds: Int,
    val mustAct: Boolean,
    val timeouts: Int,
    val rivalTimeouts: Int,
    val autoPicked: Boolean,
    val startedAtMs: Long,
)

data class BattleState(
    val active: Boolean = false,
    val wild: Boolean = true,
    val playerName: String = "",
    val opponentName: String = "",
    val party: List<BattleMon> = emptyList(),
    val activeSlot: Int = 0,
    val opponent: List<BattleMon> = emptyList(),
    val opponentSlot: Int = 0,
    val phase: BattlePhase = BattlePhase.WAITING,
    val turn: Int = 0,
    val log: List<String> = emptyList(),
    val result: String? = null,
    val battlesFinished: Int = 0,
    val pendingLearn: Pair<Long, List<Int>>? = null,
    val pendingInviteFrom: String? = null,
    /** The rules of the pending invite, when the challenger set any. */
    val pendingInviteRules: DuelRules? = null,
    /** The rules of the running duel, null outside a duel. */
    val rules: DuelRules? = null,
    val timer: TurnTimer? = null,
    /** The newest effects, oldest first (bounded). */
    val fx: List<BattleFx> = emptyList(),
) {
  val me: BattleMon?
    get() = party.getOrNull(activeSlot)

  val foe: BattleMon?
    get() = opponent.getOrNull(opponentSlot)
}

/**
 * The client side of a battle: decodes the server's battle packets into a [BattleState] the UI and
 * the script runner read, and sends the player's picks. Also carries the duel challenge flow.
 */
class BattleClient(
    private val send: (Any) -> Unit,
    private val onChange: () -> Unit,
    private val myName: () -> String?,
    private val regionId: () -> Int = { 0 },
) {
  private val lock = Any()
  private var state = BattleState()

  /** The last party the server sent, used for pp the field state leaves out. */
  private var knownParty: Map<Long, List<de.fiereu.openmmo.common.PokemonMove>> = emptyMap()
  private var knownXp: Map<Long, Int> = emptyMap()

  /** Rules the server announced for the duel about to start. */
  private var pendingDuelRules: DuelRules? = null
  private var fxSeq = 0
  private var timerSeq = 0

  /** Experience change waiting for its "gained N EXP" line: entity, before and after. */
  private var pendingExp: Triple<Long, BattleMon, BattleMon>? = null

  fun snapshot(): BattleState = synchronized(lock) { state }

  private fun update(block: (BattleState) -> BattleState) {
    synchronized(lock) { state = block(state) }
    onChange()
  }

  private fun say(line: String, animate: Boolean = true) {
    log.info { "[battle] $line" }
    update { it.copy(log = (it.log + line).takeLast(200)) }
    if (animate) fx("text", null, "text" to line)
  }

  private fun fx(kind: String, side: String?, vararg data: Pair<String, Any?>) = update {
    it.copy(fx = (it.fx + BattleFx(++fxSeq, kind, side, mapOf(*data))).takeLast(FX_KEPT))
  }

  private fun sideOf(entityId: Long): String? {
    val s = snapshot()
    return when {
      s.party.any { it.entityId == entityId } -> "me"
      s.opponent.any { it.entityId == entityId } -> "foe"
      else -> null
    }
  }

  private fun monOf(entityId: Long): BattleMon? {
    val s = snapshot()
    return s.party.firstOrNull { it.entityId == entityId }
        ?: s.opponent.firstOrNull { it.entityId == entityId }
  }

  /** True when the packet belonged to the battle or the duel flow. */
  fun handle(event: PacketEvent<*>): Boolean {
    when (val p = event.packet) {
      is BattleFieldStatePacket -> onStart(p)
      is BattleQueuedEventPacket ->
          if (p.flag) {
            update { it.copy(phase = BattlePhase.ACTION, turn = it.turn + 1) }
            snapshot().me?.let { say("What will ${it.name} do?", animate = false) }
          }
      is BattleSlotFlagEventPacket ->
          if (!p.immediate) {
            update { it.copy(phase = BattlePhase.FORCED_SWITCH) }
            say("Choose a monster to send out.", animate = false)
          }
      is DuelRulesPacket -> {
        synchronized(lock) { pendingDuelRules = p.rules }
        update { it.copy(pendingInviteRules = p.rules) }
      }
      is BattleTurnTimerPacket ->
          update {
            it.copy(
                timer =
                    TurnTimer(
                        ++timerSeq,
                        p.seconds,
                        p.mustAct,
                        p.timeouts,
                        p.rivalTimeouts,
                        p.autoPicked,
                        System.currentTimeMillis()))
          }
      is BattleEntityMoveEventPacket -> onMove(p)
      is EntityMovePpPacket -> onPp(p)
      is BattleSwitchInPacket -> onSwitchIn(p)
      is BattleEntityDeltaPacket -> onDelta(p)
      is BattleStatCountersPacket -> {
        nameOf(p.entityId)?.let { say("$it gained ${p.baseCounter} EXP. Points!") }
        flushExp(p.entityId)
      }
      is BattleSlotEventEnumPacket -> if (p.eventType.toInt() == 0) say("Got away safely!")
      is BattleListEventPacket -> {
        say("You threw a POKé BALL!")
        // The server catches every time so far, so the ball always shakes three times and holds.
        fx("ball", "foe", "shakes" to BALL_SHAKES, "caught" to true)
      }
      is BattleBulkStatePacket -> onBulk(p)
      is MoveLearnPromptPacket -> {
        val offered = p.offered.map { it.toInt() }
        update { it.copy(pendingLearn = p.entityId to offered) }
        val who = nameOf(p.entityId) ?: "Your monster"
        say(
            "$who wants to learn ${offered.joinToString { BattleNames.move(it) }}. (learn N / skip)",
            animate = false)
      }
      is PokemonContainerPacket -> {
        if (p.container == PokemonContainer.PARTY && !p.delete) {
          synchronized(lock) {
            knownParty = p.pokemon.associate { it.id to it.moves }
            knownXp = p.pokemon.associate { it.id to it.xp }
          }
        }
        return false
      }
      is DuelInvitePacket -> {
        update { it.copy(pendingInviteFrom = p.name) }
        say("${p.name} challenges you to a battle! (accept / decline)", animate = false)
      }
      is DuelInviteOutcomePacket ->
          when (p.packed.toInt()) {
            0 -> say("Your challenge was declined.", animate = false)
            1 -> say("Your challenge was accepted!", animate = false)
            else -> say("That player can't battle right now.", animate = false)
          }
      else -> return false
    }
    return true
  }

  /** Server notices go to the battle log while a battle is up. */
  fun notice(text: String) {
    // The invite packet already put this line in the log.
    if (text.endsWith("challenges you to a battle!")) return
    val s = snapshot()
    if (s.active && s.phase != BattlePhase.ENDED) say(text)
    else if (s.active || s.pendingInviteFrom != null) say(text, animate = false)
  }

  private fun onStart(p: BattleFieldStatePacket) {
    val known = synchronized(lock) { knownParty }
    val xps = synchronized(lock) { knownXp }
    val party =
        p.playerParty.map { b ->
          val pp = known[b.entityId]
          BattleMon(
              slot = b.slot,
              entityId = b.entityId,
              species = b.species.toInt(),
              level = b.level.toInt(),
              hp = b.currentHp.toInt(),
              maxHp = b.maxHp.toInt(),
              xp = xps[b.entityId],
              moves =
                  b.moveIds.mapIndexedNotNull { i, id ->
                    if (id.toInt() == 0) null
                    else {
                      val max = BattleNames.maxPp(id.toInt())
                      val cur = pp?.getOrNull(i)?.takeIf { it.id == id }?.pp?.toInt() ?: max
                      BattleMove(id.toInt(), cur, max)
                    }
                  },
          )
        }
    val opponent =
        p.opponentParty.map { b ->
          BattleMon(
              b.slot,
              b.entityId,
              b.species.toInt(),
              b.level.toInt(),
              b.currentHp.toInt(),
              b.maxHp.toInt(),
              revealed = b.revealed)
        }
    val wild = p.opposing == OpposingSide.WILD
    val invitedBy = snapshot().pendingInviteFrom
    val pvp = !wild && p.trainerId.toInt() == 0
    val duelRules = synchronized(lock) { pendingDuelRules.also { pendingDuelRules = null } }
    update {
      it.copy(
          active = true,
          wild = wild,
          playerName = p.playerName,
          opponentName =
              when {
                wild -> ""
                p.trainerId.toInt() != 0 ->
                    BattleNames.trainer(regionId(), p.trainerId.toInt()) ?: "Trainer"
                else -> invitedBy ?: it.opponentName
              },
          party = party,
          activeSlot = p.activeSlot,
          opponent = opponent,
          opponentSlot = p.opponentActiveSlot,
          phase = BattlePhase.WAITING,
          turn = 0,
          log = emptyList(),
          result = null,
          pendingInviteFrom = null,
          pendingInviteRules = null,
          rules = if (pvp) duelRules ?: DuelRules.DEFAULT else null,
          timer = null,
      )
    }
    synchronized(lock) { pendingExp = null }
    val s = snapshot()
    fx(
        "start",
        null,
        "wild" to wild,
        "pvp" to pvp,
        "opponentName" to s.opponentName,
        "me" to s.me?.let(::monFx),
        "foe" to s.foe?.let(::monFx))
    val foe = s.foe
    if (wild) say("A wild ${foe?.name} (Lv${foe?.level}) appeared!")
    else say("${s.opponentName.ifEmpty { "A trainer" }} sent out ${foe?.name}!")
    say("Go! ${s.me?.name}!")
  }

  private fun nameOf(entityId: Long): String? {
    val s = snapshot()
    s.party
        .firstOrNull { it.entityId == entityId }
        ?.let {
          return it.name
        }
    s.opponent
        .firstOrNull { it.entityId == entityId }
        ?.let {
          return "Foe ${it.name}"
        }
    return null
  }

  private fun mapMon(entityId: Long, f: (BattleMon) -> BattleMon) = update { s ->
    s.copy(
        party = s.party.map { if (it.entityId == entityId) f(it) else it },
        opponent = s.opponent.map { if (it.entityId == entityId) f(it) else it },
    )
  }

  private fun onMove(p: BattleEntityMoveEventPacket) {
    val attacker = nameOf(p.sourceEntity) ?: "?"
    val moveId = p.sourceMove.toInt()
    say("$attacker used ${BattleNames.move(moveId)}!")
    val firstTarget = p.targets.firstOrNull()
    val hits =
        p.targets.any { t ->
          t.subEvents.any { it.body is BattleEventBody.HpUpdate } && t.entityId != p.sourceEntity
        }
    fx(
        "move",
        sideOf(p.sourceEntity),
        "move" to BattleNames.move(moveId),
        "type" to BattleNames.moveType(moveId),
        "power" to BattleNames.power(moveId),
        "target" to firstTarget?.let { sideOf(it.entityId) },
        "hits" to hits)
    for (t in p.targets) {
      val outcome = t.targetMove.toInt()
      if (t.subEvents.isEmpty() && outcome == OUTCOME_MISSED) {
        fx("miss", sideOf(t.entityId))
        say("$attacker's attack missed!")
      }
      if (t.subEvents.isEmpty() && outcome == OUTCOME_FAILED) say("But it failed!")
      val target = nameOf(t.entityId) ?: "?"
      var effectivenessSaid = false
      fun effectiveness() {
        if (effectivenessSaid) return
        effectivenessSaid = true
        if (outcome and SUPER_EFFECTIVE_BIT != 0) say("It's super effective!")
        if (outcome and NOT_VERY_EFFECTIVE_BIT != 0) say("It's not very effective...")
      }
      for (e in t.subEvents) {
        when (val body = e.body) {
          is BattleEventBody.HpUpdate -> {
            val before = monOf(t.entityId)
            mapMon(t.entityId) { it.copy(hp = body.currentHp.toInt()) }
            fx(
                "hp",
                sideOf(t.entityId),
                "from" to before?.hp?.coerceAtLeast(0),
                "hp" to body.currentHp.toInt().coerceAtLeast(0),
                "maxHp" to before?.maxHp,
                "effect" to
                    when {
                      outcome and SUPER_EFFECTIVE_BIT != 0 -> "super"
                      outcome and NOT_VERY_EFFECTIVE_BIT != 0 -> "weak"
                      else -> "normal"
                    })
            effectiveness()
            if (body.currentHp <= 0) {
              fx("faint", sideOf(t.entityId))
              say("$target fainted!")
            }
          }
          is BattleEventBody.StatChange -> {
            val d = body.stageDelta.toInt()
            val how =
                when {
                  d >= 2 -> "rose sharply"
                  d > 0 -> "rose"
                  d <= -2 -> "harshly fell"
                  else -> "fell"
                }
            fx(
                "stat",
                sideOf(t.entityId),
                "stat" to BattleNames.stat(body.stat.toInt()),
                "delta" to d)
            say("$target's ${BattleNames.stat(body.stat.toInt())} $how!")
          }
          is BattleEventBody.Faint -> {
            fx("faint", sideOf(t.entityId))
            say("$target fainted!")
          }
          is BattleEventBody.MoveFailed -> say("But it failed!")
          BattleEventBody.EffectivenessMessage -> Unit
        }
      }
      effectiveness()
    }
    update { if (it.phase == BattlePhase.ACTION) it.copy(phase = BattlePhase.WAITING) else it }
  }

  private fun onPp(p: EntityMovePpPacket) =
      mapMon(p.entityId) { mon ->
        mon.copy(
            moves =
                mon.moves.mapIndexed { i, m ->
                  if (i == p.moveSlot.toInt()) m.copy(pp = p.ppValue.toInt()) else m
                })
      }

  private fun onSwitchIn(p: BattleSwitchInPacket) {
    val b = p.mon
    if (p.side.toInt() == 0) {
      update { s ->
        val party =
            s.party.map { m ->
              if (m.slot == p.newSlot && p.fullBlock)
                  m.copy(hp = b.currentHp.toInt(), maxHp = b.maxHp.toInt(), level = b.level.toInt())
              else m
            }
        s.copy(party = party, activeSlot = p.newSlot, phase = BattlePhase.WAITING)
      }
      fx("switch", "me", "mon" to snapshot().me?.let(::monFx))
      say("Go! ${snapshot().me?.name}!")
    } else {
      update { s ->
        val existing = s.opponent.firstOrNull { it.slot == p.newSlot }
        val mon =
            if (p.fullBlock || existing == null || !existing.revealed)
                BattleMon(
                    p.newSlot,
                    b.entityId,
                    b.species.toInt(),
                    b.level.toInt(),
                    b.currentHp.toInt(),
                    b.maxHp.toInt())
            else existing
        val list = s.opponent.toMutableList()
        val i = list.indexOfFirst { it.slot == p.newSlot }
        if (i >= 0) list[i] = mon else list += mon
        s.copy(opponent = list, opponentSlot = p.newSlot)
      }
      val who = snapshot().opponentName.ifEmpty { "The foe" }
      fx("switch", "foe", "mon" to snapshot().foe?.let(::monFx))
      say("$who sent out ${snapshot().foe?.name}!")
    }
  }

  private fun onDelta(p: BattleEntityDeltaPacket) {
    val before = snapshot().party.firstOrNull { it.entityId == p.entityId }
    if (p.experience != null) flushExp(null)
    mapMon(p.entityId) { m ->
      var n = m
      p.experience?.let { n = n.copy(level = it.level.toInt(), xp = it.points) }
      p.statValues?.let { n = n.copy(maxHp = it[0].toInt()) }
      p.currentHp?.let { n = n.copy(hp = it.toInt()) }
      p.moves?.let { mv ->
        n =
            n.copy(
                moves =
                    mv.slots
                        .filter { it.first.toInt() != 0 }
                        .map { (id, pp) ->
                          BattleMove(id.toInt(), pp.toInt(), BattleNames.maxPp(id.toInt()))
                        })
      }
      n
    }
    val after = snapshot().party.firstOrNull { it.entityId == p.entityId }
    if (p.experience != null && before != null && after != null) {
      // Shown once the "gained N EXP" line arrives, so the bar fills after the text.
      synchronized(lock) { pendingExp = Triple(p.entityId, before, after) }
    } else if (before != null && after != null && after.maxHp != before.maxHp) {
      fx(
          "hp",
          sideOf(p.entityId),
          "from" to before.hp,
          "hp" to after.hp,
          "maxHp" to after.maxHp,
          "effect" to "heal")
    }
  }

  /** Plays the experience change for [entityId] (or any pending one when null). */
  private fun flushExp(entityId: Long?) {
    val (id, before, after) =
        synchronized(lock) {
          val pe = pendingExp ?: return
          if (entityId != null && pe.first != entityId) return
          pendingExp = null
          pe
        }
    val from = BattleNames.expFraction(before.species, before.level, before.xp) ?: 0.0
    val to = BattleNames.expFraction(after.species, after.level, after.xp) ?: 0.0
    val side = sideOf(id)
    if (after.level > before.level) {
      fx("exp", side, "from" to from, "to" to 1.0)
      fx("level", side, "level" to after.level)
      say("${after.name} grew to Lv. ${after.level}!")
      fx("exp", side, "from" to 0.0, "to" to to)
    } else {
      fx("exp", side, "from" to from, "to" to to)
    }
  }

  private fun monFx(m: BattleMon): Map<String, Any?> =
      mapOf(
          "dex" to m.species,
          "name" to m.name,
          "level" to m.level,
          "hp" to m.hp.coerceAtLeast(0),
          "maxHp" to m.maxHp)

  private fun onBulk(p: BattleBulkStatePacket) {
    if (p.flag != BATTLE_END_FLAG) return
    val s = snapshot()
    val result =
        when {
          p.phase == FLED_PHASE -> "FLED"
          s.party.isNotEmpty() && s.party.all { it.fainted } -> "DEFEAT"
          s.opponent.isNotEmpty() && s.opponent.all { it.fainted } -> "VICTORY"
          s.log.any { it.contains("POKé BALL") } -> "CAUGHT"
          s.log.any { it.contains("won the battle") } ->
              if (s.log.last { it.contains("won the battle") }.startsWith("${myName()} ")) "VICTORY"
              else "DEFEAT"
          else -> "ENDED"
        }
    if (p.prizeMoney > 0) say("You got ¥${p.prizeMoney} for winning!")
    when (result) {
      "VICTORY" -> say("You won!")
      "DEFEAT" -> say("You lost...")
      "CAUGHT" -> say("Gotcha! ${s.foe?.name} was caught!")
      else -> Unit
    }
    flushExp(null)
    fx("end", null, "result" to result)
    update {
      it.copy(
          phase = BattlePhase.ENDED,
          result = result,
          battlesFinished = it.battlesFinished + 1,
          timer = null)
    }
    log.info { "battle over: $result" }
    // The server holds the result until the client says it is back on the map.
    send(MapLoadedAckPacket())
  }

  /** Leaves the end screen. The overworld is already live on the server side. */
  fun close() = update { it.copy(active = false) }

  // --- actions -----------------------------------------------------------------------------

  fun fight(moveIndex: Int): String? {
    val s = snapshot()
    if (s.phase != BattlePhase.ACTION) return "not your turn"
    val move = s.me?.moves?.getOrNull(moveIndex) ?: return "no move ${moveIndex + 1}"
    if (move.pp <= 0 && s.me!!.moves.any { it.pp > 0 }) return "${move.name} has no PP left"
    update { it.copy(phase = BattlePhase.WAITING) }
    send(BattleActionSelectPacket(0, BattleAction.MOVE, move.id.toShort(), 0, 0))
    return null
  }

  fun switchTo(partyIndex: Int): String? {
    val s = snapshot()
    if (s.phase != BattlePhase.ACTION && s.phase != BattlePhase.FORCED_SWITCH) return "not now"
    val mon = s.party.getOrNull(partyIndex) ?: return "no monster ${partyIndex + 1}"
    if (mon.fainted) return "${mon.name} has fainted"
    if (partyIndex == s.activeSlot) return "${mon.name} is already out"
    update { it.copy(phase = BattlePhase.WAITING) }
    send(BattleActionSelectPacket(0, BattleAction.SWITCH, partyIndex.toShort(), 0, 0))
    return null
  }

  fun run(): String? {
    if (snapshot().phase != BattlePhase.ACTION) return "not your turn"
    update { it.copy(phase = BattlePhase.WAITING) }
    send(BattleActionSelectPacket(0, BattleAction.RUN, 0, 0, 0))
    return null
  }

  /** BAG: the only battle item so far is a Poke Ball thrown at the wild monster. */
  fun throwBall(): String? {
    val s = snapshot()
    if (s.phase != BattlePhase.ACTION) return "not your turn"
    val target = s.foe?.entityId ?: 0
    update { it.copy(phase = BattlePhase.WAITING) }
    send(BattleActionSelectPacket(0, BattleAction.ITEM, BattleNames.pokeBall, target, 0))
    return null
  }

  /** Puts the offered move in [slot], or keeps the moveset when [slot] is null. */
  fun learn(slot: Int?): String? {
    val (entityId, offered) = snapshot().pendingLearn ?: return "nothing to learn"
    val mon = snapshot().party.firstOrNull { it.entityId == entityId }
    val current =
        mon?.moves?.map { it.id.toShort() } ?: knownParty[entityId]?.map { it.id } ?: emptyList()
    val padded = (current + List(4) { 0.toShort() }).take(4).toMutableList()
    if (slot != null) {
      if (slot !in 0..3) return "slot must be 1-4"
      padded[slot] = offered.first().toShort()
    }
    send(MoveLearnReplyPacket(entityId, padded, offered.map { it.toShort() }))
    update { it.copy(pendingLearn = null) }
    return null
  }

  /** Challenges [name] under [rules]; default rules use the retail-shaped packet. */
  fun challenge(name: String, rules: DuelRules = DuelRules.DEFAULT) {
    update { it.copy(opponentName = name) }
    if (rules != DuelRules.DEFAULT) {
      send(DuelRulesChallengePacket(name, rules))
      return
    }
    send(
        DuelChallengePacket(
            targetPlayerName = name,
            battleTypeId = 0,
            timed = false,
            battleFormat = 0,
            typeRestriction = 0,
            natureRestriction = 0,
            allowedFormat = 0,
            itemLevelCap = null,
            natureCap = null,
            items = null,
            tier = null,
        ))
  }

  fun answerInvite(accept: Boolean): String? {
    val from = snapshot().pendingInviteFrom ?: return "no challenge to answer"
    if (!accept) update { it.copy(pendingInviteFrom = null, pendingInviteRules = null) }
    send(InGameChallengeResponsePacket(accept, from))
    return null
  }
}
