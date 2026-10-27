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
import de.fiereu.openmmo.net.game.packets.battle.BattleExtActionPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleExtKind
import de.fiereu.openmmo.net.game.packets.battle.BattleExtPacket
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
import de.fiereu.openmmo.net.game.packets.battle.EvolutionReplyPacket
import de.fiereu.openmmo.net.game.packets.battle.OpposingSide
import de.fiereu.openmmo.net.game.packets.battle.moves.MoveLearnPromptPacket
import de.fiereu.openmmo.net.game.packets.battle.moves.MoveLearnReplyPacket
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

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

  /** r3-battle: the move's target kind (SELECTED, BOTH, USER...) for the doubles target picker. */
  fun target(id: Int): String? = moves.get(id)?.target?.name

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
    /** r3-battle: major status code (slp, psn, tox, brn, frz, par) or null. */
    val status: String? = null,
    val confused: Boolean = false,
    /** Own monsters only: ability and held item names. */
    val ability: String? = null,
    val item: String? = null,
    val substitute: Boolean = false,
    /** Gen 3 shiny, as the battle block carries it. */
    val shiny: Boolean = false,
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

/** One bag item usable in battle: [target] is foe, party, active or none. */
data class BattleBagItem(
    val id: Int,
    val name: String,
    val count: Int,
    val target: String,
    val pocket: String,
    val perMove: Boolean = false,
)

/** An evolution about to play: the browser shows the scene and answers proceed / stop (B). */
data class EvolutionScene(
    val id: Long,
    val fromDex: Int,
    val toDex: Int,
    val name: String,
    val toName: String,
    /** start, done or cancelled. */
    val phase: String,
    val learned: List<String> = emptyList(),
    val seq: Int = 0,
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
    // --- r3-battle extension state ---
    /** Monsters out per side: 1 (single) or 2 (double). */
    val format: Int = 1,
    /** Party slot per battle position, -1 for an empty position. */
    val actives: List<Int> = listOf(0),
    val foeActives: List<Int> = listOf(0),
    /** Battle background key (graphics/battle_environment name). */
    val terrain: String = "tall_grass",
    /** Music key: wild, trainer, gym, elite, champion, rival, team, pvp. */
    val music: String? = null,
    val weather: String = "none",
    /** The opposing trainer's front sprite key (TRAINER_PIC_ suffix, lower case) and class. */
    val trainerPic: String? = null,
    val trainerClass: String? = null,
    /** Positions that owe a replacement for a fainted monster. */
    val owedSwitches: List<Int> = emptyList(),
    /** Positions that already picked this turn (doubles). */
    val chosen: List<Int> = emptyList(),
    /** An evolution scene waiting for the player's answer (also outside battles). */
    val evolution: EvolutionScene? = null,
    /** Bag items usable in this battle, from the server. */
    val bag: List<BattleBagItem> = emptyList(),
    /** r5-frontier: positions another player (or an NPC partner) picks for in a multi battle. */
    val notMine: List<Int> = emptyList(),
) {
  /** The position whose action the player picks next, or null when all picked. */
  val choosingPos: Int?
    get() =
        actives.indices.firstOrNull { pos ->
          pos !in chosen &&
              pos !in notMine &&
              party.getOrNull(actives[pos])?.let { !it.fainted } == true
        }

  /** r5-frontier: the replacements this player owes (not the partner's). */
  val myOwedSwitches: List<Int>
    get() = owedSwitches.filter { it !in notMine }

  /** The monster out at [pos] on our side. */
  fun mine(pos: Int): BattleMon? = actives.getOrNull(pos)?.let { party.getOrNull(it) }

  fun foeAt(pos: Int): BattleMon? =
      foeActives.getOrNull(pos)?.let { s -> opponent.firstOrNull { it.slot == s } }

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
    /** r5-frontier: this player's character id, for the positions of a multi battle. */
    private val myId: () -> Long? = { null },
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

  /** r3-battle: set when an extension ball event played the throw. */
  private var ballShown = false

  /** r3-battle: the last ball held, so the battle end reads CAUGHT whatever the ball. */
  private var caughtSeen = false

  /** r3-battle: the trainer name from the extended state sent just before the battle opens. */
  private var extTrainerName: String? = null

  /** r3-battle: the position the next retail switch-in fills. */
  private var pendingSwitchPos: Int? = null

  private var evoSeq = 0

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
            update {
              val next =
                  it.copy(phase = BattlePhase.ACTION, turn = it.turn + 1, chosen = emptyList())
              // r5-frontier: in a multi battle the partner's position is not ours to pick.
              if (next.notMine.isNotEmpty() && next.choosingPos == null)
                  next.copy(phase = BattlePhase.WAITING)
              else next
            }
            snapshot().me?.let { say("What will ${it.name} do?", animate = false) }
          }
      is BattleSlotFlagEventPacket ->
          if (!p.immediate) {
            val mine = snapshot().let { it.notMine.isEmpty() || it.myOwedSwitches.isNotEmpty() }
            if (mine) {
              update { it.copy(phase = BattlePhase.FORCED_SWITCH) }
              say("Choose a monster to send out.", animate = false)
            } else update { it.copy(phase = BattlePhase.WAITING) }
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
        // r3-battle: the extension ball event already played the throw with its real shakes.
        // The retail throw event only follows a catch.
        synchronized(lock) { caughtSeen = true }
        if (!synchronized(lock) { ballShown.also { ballShown = false } }) {
          say("You threw a POKé BALL!")
          fx("ball", "foe", "shakes" to BALL_SHAKES, "caught" to true)
        }
      }
      is BattleExtPacket -> onExt(p.json)
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
              shiny = b.shiny,
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
              revealed = b.revealed,
              shiny = b.shiny)
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
                    synchronized(lock) { extTrainerName }
                        ?: BattleNames.trainer(regionId(), p.trainerId.toInt())
                        ?: "Trainer"
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
    synchronized(lock) {
      pendingExp = null
      caughtSeen = false
    }
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
        "hits" to hits,
        "id" to p.sourceEntity,
        "targetId" to firstTarget?.entityId,
        "targets" to p.targets.map { it.entityId })
    for (t in p.targets) {
      val outcome = t.targetMove.toInt()
      if (t.subEvents.isEmpty() && outcome == OUTCOME_MISSED) {
        fx("miss", sideOf(t.entityId), "id" to t.entityId)
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
                "id" to t.entityId,
                "effect" to
                    when {
                      outcome and SUPER_EFFECTIVE_BIT != 0 -> "super"
                      outcome and NOT_VERY_EFFECTIVE_BIT != 0 -> "weak"
                      else -> "normal"
                    })
            effectiveness()
            if (body.currentHp <= 0) {
              fx("faint", sideOf(t.entityId), "id" to t.entityId)
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
                "delta" to d,
                "id" to t.entityId)
            say("$target's ${BattleNames.stat(body.stat.toInt())} $how!")
          }
          is BattleEventBody.Faint -> {
            fx("faint", sideOf(t.entityId), "id" to t.entityId)
            say("$target fainted!")
          }
          is BattleEventBody.MoveFailed -> say("But it failed!")
          BattleEventBody.EffectivenessMessage -> Unit
        }
      }
      effectiveness()
    }
    update {
      if (it.phase == BattlePhase.ACTION && it.choosingPos == null)
          it.copy(phase = BattlePhase.WAITING)
      else it
    }
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
    val pos = synchronized(lock) { pendingSwitchPos.also { pendingSwitchPos = null } } ?: 0
    if (p.side.toInt() == 0) {
      update { s ->
        val list = s.actives.toMutableList()
        while (list.size <= pos) list += -1
        list[pos] = p.newSlot
        s.copy(actives = list)
      }
      update { s ->
        val party =
            s.party.map { m ->
              if (m.slot == p.newSlot && p.fullBlock)
                  m.copy(hp = b.currentHp.toInt(), maxHp = b.maxHp.toInt(), level = b.level.toInt())
              else m
            }
        s.copy(
            party = party,
            activeSlot = if (pos == 0) p.newSlot else s.activeSlot,
            phase = BattlePhase.WAITING)
      }
      val out = snapshot().party.firstOrNull { it.slot == p.newSlot }
      fx("switch", "me", "mon" to out?.let(::monFx), "pos" to pos)
      say("Go! ${out?.name}!")
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
                    b.maxHp.toInt(),
                    shiny = b.shiny)
            else existing
        val list = s.opponent.toMutableList()
        val i = list.indexOfFirst { it.slot == p.newSlot }
        if (i >= 0) list[i] = mon else list += mon
        val fa = s.foeActives.toMutableList()
        while (fa.size <= pos) fa += -1
        fa[pos] = p.newSlot
        s.copy(
            opponent = list,
            opponentSlot = if (pos == 0) p.newSlot else s.opponentSlot,
            foeActives = fa)
      }
      val who = snapshot().opponentName.ifEmpty { "The foe" }
      val out = snapshot().opponent.firstOrNull { it.slot == p.newSlot }
      fx("switch", "foe", "mon" to out?.let(::monFx), "pos" to pos)
      say("$who sent out ${out?.name}!")
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
          "maxHp" to m.maxHp,
          "status" to m.status,
          "shiny" to m.shiny)

  private fun onBulk(p: BattleBulkStatePacket) {
    if (p.flag != BATTLE_END_FLAG) return
    val s = snapshot()
    val result =
        when {
          p.phase == FLED_PHASE -> "FLED"
          s.party.isNotEmpty() && s.party.all { it.fainted } -> "DEFEAT"
          s.opponent.isNotEmpty() && s.opponent.all { it.fainted } -> "VICTORY"
          synchronized(lock) { caughtSeen } -> "CAUGHT"
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

  fun fight(moveIndex: Int): String? = fightAt(null, moveIndex, null, null)

  /**
   * r3-battle: the move in slot [moveIndex] for position [pos] (null = the next one to pick), aimed
   * at [targetSide] ("foe" / "me") [targetPos]; null lets the server pick.
   */
  fun fightAt(pos: Int?, moveIndex: Int, targetSide: String?, targetPos: Int?): String? {
    val s = snapshot()
    if (s.phase != BattlePhase.ACTION) return "not your turn"
    val at = pos ?: s.choosingPos ?: return "nothing to pick"
    val mon = s.mine(at) ?: return "no monster at position ${at + 1}"
    val move = mon.moves.getOrNull(moveIndex) ?: return "no move ${moveIndex + 1}"
    if (move.pp <= 0 && mon.moves.any { it.pp > 0 }) return "${move.name} has no PP left"
    picked(at)
    if (s.format <= 1 && targetSide == null) {
      send(BattleActionSelectPacket(0, BattleAction.MOVE, move.id.toShort(), 0, 0))
    } else {
      val side =
          when (targetSide) {
            "me" -> 0
            "foe" -> 1
            else -> -1
          }
      send(BattleExtActionPacket(at, BattleExtKind.MOVE, move.id.toShort(), side, targetPos ?: -1))
    }
    return null
  }

  /** Marks [pos] picked; the phase leaves ACTION once every position has picked. */
  private fun picked(pos: Int) = update { s ->
    val chosen = (s.chosen + pos).distinct()
    val next = s.copy(chosen = chosen)
    if (next.choosingPos == null) next.copy(phase = BattlePhase.WAITING) else next
  }

  /**
   * r3-battle: uses bag item [itemId] from position [pos] on party monster [targetEntity] (0 = the
   * active one / balls), with [moveSlot] for one-move PP items.
   */
  fun useItem(itemId: Int, targetEntity: Long = 0, moveSlot: Int = -1, pos: Int? = null): String? {
    val s = snapshot()
    if (s.phase != BattlePhase.ACTION) return "not your turn"
    val at = pos ?: s.choosingPos ?: 0
    picked(at)
    send(
        BattleExtActionPacket(at, BattleExtKind.ITEM, itemId.toShort(), -1, moveSlot, targetEntity))
    return null
  }

  /** r3-battle: answers an evolution scene: [proceed] false = B pressed. */
  fun answerEvolution(proceed: Boolean): String? {
    val evo = snapshot().evolution ?: return "nothing is evolving"
    if (evo.phase != "start") return "nothing is evolving"
    send(EvolutionReplyPacket(evo.id, proceed))
    update { it.copy(evolution = evo.copy(phase = if (proceed) "evolving" else "stopping")) }
    return null
  }

  fun dismissEvolution() = update { it.copy(evolution = null) }

  fun switchTo(partyIndex: Int): String? {
    val s = snapshot()
    if (s.phase != BattlePhase.ACTION && s.phase != BattlePhase.FORCED_SWITCH) return "not now"
    val mon = s.party.getOrNull(partyIndex) ?: return "no monster ${partyIndex + 1}"
    if (mon.fainted) return "${mon.name} has fainted"
    if (partyIndex in s.actives) return "${mon.name} is already out"
    val pos =
        if (s.phase == BattlePhase.FORCED_SWITCH) s.myOwedSwitches.firstOrNull() ?: 0
        else s.choosingPos ?: 0
    if (s.phase == BattlePhase.FORCED_SWITCH) {
      update { it.copy(phase = BattlePhase.WAITING) }
    } else picked(pos)
    if (s.format <= 1)
        send(BattleActionSelectPacket(0, BattleAction.SWITCH, partyIndex.toShort(), 0, 0))
    else send(BattleExtActionPacket(pos, BattleExtKind.SWITCH, partyIndex.toShort()))
    return null
  }

  fun run(): String? {
    if (snapshot().phase != BattlePhase.ACTION) return "not your turn"
    update { it.copy(phase = BattlePhase.WAITING) }
    send(BattleActionSelectPacket(0, BattleAction.RUN, 0, 0, 0))
    return null
  }

  /** BAG: the only battle item so far is a Poke Ball thrown at the wild monster. */
  fun throwBall(itemId: Int = BattleNames.pokeBall.toInt()): String? {
    val s = snapshot()
    if (s.phase != BattlePhase.ACTION) return "not your turn"
    val target = s.foe?.entityId ?: 0
    update { it.copy(phase = BattlePhase.WAITING) }
    send(BattleActionSelectPacket(0, BattleAction.ITEM, itemId.toShort(), target, 0))
    return null
  }

  // --- r3-battle extension channel -----------------------------------------------------------

  private fun sideOfIdOrNull(id: Long): String? = sideOf(id)

  private fun onExt(json: String) {
    val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return
    when (root["t"]?.jsonPrimitive?.contentOrNull) {
      "state" -> onExtState(root)
      "events" -> root["e"]?.jsonArray?.forEach { onExtEvent(it.jsonObject) }
      "evo" -> onEvo(root)
    }
  }

  private fun JsonObject.str(k: String): String? = this[k]?.jsonPrimitive?.contentOrNull

  private fun JsonObject.int(k: String): Int? = this[k]?.jsonPrimitive?.intOrNull

  private fun JsonObject.long(k: String): Long? = this[k]?.jsonPrimitive?.longOrNull

  private fun JsonObject.bool(k: String): Boolean = this[k]?.jsonPrimitive?.booleanOrNull ?: false

  private fun onExtState(o: JsonObject) {
    val me = o["me"]?.jsonObject
    val foe = o["foe"]?.jsonObject
    fun actives(side: JsonObject?) = side?.get("actives")?.jsonArray?.map { it.jsonPrimitive.int }
    val monInfo =
        (me?.get("mons")?.jsonArray.orEmpty() + foe?.get("mons")?.jsonArray.orEmpty()).associate {
          val m = it.jsonObject
          m.long("id")!! to m
        }
    val trainer = o["trainer"]?.jsonObject
    synchronized(lock) { extTrainerName = trainer?.str("name")?.takeIf { it.isNotBlank() } }
    update { s ->
      fun merge(list: List<BattleMon>) =
          list.map { b ->
            val m = monInfo[b.entityId] ?: return@map b
            b.copy(
                status = m.str("status"),
                confused = m.bool("confused"),
                ability = m.str("ability") ?: b.ability,
                item = if (m.containsKey("item")) m.str("item") else b.item,
                substitute = m.bool("substitute"),
                hp = m.int("hp") ?: b.hp,
                maxHp = m.int("maxHp") ?: b.maxHp)
          }
      val owed = o["owedSwitches"]?.jsonArray?.map { it.jsonPrimitive.int }.orEmpty()
      val myCid = myId()
      val notMine =
          o["posOwners"]?.jsonArray?.mapIndexedNotNull { pos, v ->
            pos.takeIf { v.jsonPrimitive.longOrNull != myCid }
          } ?: s.notMine
      val chosen = o["chosen"]?.jsonArray?.map { it.jsonPrimitive.int }.orEmpty()
      val acts = actives(me) ?: s.actives
      s.copy(
          format = o.int("format") ?: s.format,
          terrain = o.str("terrain") ?: s.terrain,
          music = o.str("music") ?: s.music,
          weather = o.str("weather") ?: s.weather,
          trainerPic = trainer?.str("pic") ?: s.trainerPic,
          opponentName = trainer?.str("name")?.takeIf { it.isNotBlank() } ?: s.opponentName,
          trainerClass = trainer?.str("class") ?: s.trainerClass,
          actives = acts,
          foeActives = actives(foe) ?: s.foeActives,
          activeSlot = acts.firstOrNull { it >= 0 } ?: s.activeSlot,
          opponentSlot = actives(foe)?.firstOrNull { it >= 0 } ?: s.opponentSlot,
          owedSwitches = owed,
          notMine = notMine,
          chosen = if (s.phase == BattlePhase.ACTION) (s.chosen + chosen).distinct() else chosen,
          party = merge(s.party),
          opponent = merge(s.opponent),
          bag =
              o["bag"]?.jsonArray?.mapNotNull {
                val b = it.jsonObject
                BattleBagItem(
                    b.int("id") ?: return@mapNotNull null,
                    b.str("name") ?: "",
                    b.int("count") ?: 0,
                    b.str("target") ?: "none",
                    b.str("pocket") ?: "medicine",
                    b.bool("perMove"))
              } ?: s.bag,
      )
    }
  }

  private fun onExtEvent(e: JsonObject) {
    val id = e.long("id") ?: 0L
    val side = if (id != 0L) sideOfIdOrNull(id) else null
    when (e.str("k")) {
      "text" -> say(e.str("text") ?: return)
      "hp" -> {
        val before = monOf(id) ?: return
        val hp = e.int("hp") ?: return
        mapMon(id) { it.copy(hp = hp) }
        fx(
            "hp",
            side,
            "from" to before.hp.coerceAtLeast(0),
            "hp" to hp.coerceAtLeast(0),
            "maxHp" to before.maxHp,
            "effect" to if (hp > before.hp) "heal" else "normal",
            "reason" to e.str("reason"),
            "id" to id)
        if (hp <= 0 && before.hp > 0) {
          fx("faint", side, "id" to id)
          say("${nameOf(id)} fainted!")
        }
        if (hp > 0 && before.hp <= 0) fx("revive", side, "id" to id)
      }
      "status" -> {
        val code = e.str("status")
        mapMon(id) { it.copy(status = code) }
        fx("status", side, "status" to code, "id" to id)
      }
      "volatile" -> {
        val kind = e.str("kind")
        val on = e.bool("on")
        when (kind) {
          "confused" -> mapMon(id) { it.copy(confused = on) }
          "substitute" -> mapMon(id) { it.copy(substitute = on) }
        }
        fx("volatile", side, "kind" to kind, "on" to on, "id" to id)
      }
      "ability" ->
          fx("ability", side, "ability" to e.str("ability"), "name" to nameOf(id), "id" to id)
      "item" -> {
        if (e.bool("consumed")) mapMon(id) { it.copy(item = null) }
        fx(
            "item",
            side,
            "item" to e.str("item"),
            "consumed" to e.bool("consumed"),
            "id" to id,
            "name" to nameOf(id))
      }
      "weather" -> {
        val w = e.str("weather") ?: "none"
        update { it.copy(weather = w) }
        fx("weather", null, "weather" to w)
      }
      "stat" -> {
        val d = e.int("delta") ?: 0
        val stat = BattleNames.stat(e.int("stat") ?: 0)
        val how =
            when {
              d >= 2 -> "rose sharply"
              d > 0 -> "rose"
              d <= -2 -> "harshly fell"
              else -> "fell"
            }
        fx("stat", side, "stat" to stat, "delta" to d, "id" to id)
        say("${nameOf(id)}'s $stat $how!")
      }
      "miss" -> fx("miss", side, "id" to id)
      "switch" -> synchronized(lock) { pendingSwitchPos = e.int("pos") }
      "ball" -> {
        val ball = e.str("ball") ?: "POKé BALL"
        val shakes = e.int("shakes") ?: 0
        val caught = e.bool("caught")
        synchronized(lock) {
          ballShown = true
          if (caught) caughtSeen = true
        }
        say("You threw a ${ball.uppercase().replace("POKÉ", "POKé")}!")
        fx("ball", "foe", "shakes" to shakes, "caught" to caught, "ball" to ball)
        if (!caught) synchronized(lock) { ballShown = false }
      }
      "fled" -> say(if (e.int("side") == 1) "The wild monster fled!" else "Got away safely!")
    }
  }

  private fun onEvo(o: JsonObject) {
    val phase = o.str("phase") ?: return
    val id = o.long("id") ?: return
    update { s ->
      val prev = s.evolution
      val scene =
          if (phase == "start")
              EvolutionScene(
                  id,
                  o.int("fromDex") ?: 0,
                  o.int("toDex") ?: 0,
                  o.str("name") ?: "",
                  o.str("toName") ?: "",
                  "start",
                  seq = ++evoSeq)
          else
              (prev ?: EvolutionScene(id, 0, o.int("toDex") ?: 0, "", "", phase)).copy(
                  phase = phase,
                  learned = o["learned"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty())
      s.copy(evolution = scene)
    }
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
