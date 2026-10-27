package de.fiereu.openmmo.server.game.frontier

import de.fiereu.openmmo.common.enums.MoveTarget
import de.fiereu.openmmo.moves.MoveDef
import de.fiereu.openmmo.server.game.battle.BattleAi
import de.fiereu.openmmo.server.game.battle.BattleChoice
import de.fiereu.openmmo.server.game.battle.BattleEvent
import de.fiereu.openmmo.server.game.battle.BattleInstance
import de.fiereu.openmmo.server.game.battle.BattleMonState
import de.fiereu.openmmo.server.game.battle.BattleNames
import de.fiereu.openmmo.server.game.battle.BattlerRef
import de.fiereu.openmmo.server.game.battle.FacilityHooks
import de.fiereu.openmmo.server.game.battle.TurnEngine
import de.fiereu.openmmo.typechart.TypeChart
import kotlin.random.Random

/** PALACE_MOVE_GROUP_*. */
enum class PalaceGroup {
  ATTACK,
  DEFENSE,
  SUPPORT,
}

/**
 * The Battle Palace (and Verdanturf Battle Tent): battle_gfx_sfx_util.c
 * ChooseMoveAndTargetInBattlePalace and battle_script_commands.c
 * gBattlePalaceNatureToMoveGroupLikelihood. Each monster picks a move group by its nature (one
 * table above half hp, another at or below half), then the AI picks within the group. A monster
 * with no usable move in the group picks at random from the others and then has a 50% chance to
 * be "incapable of using its power". Below half hp a monster shows its nature's flavor line.
 */
object PalaceRules {
  /** PALACE_STYLE(atk, def, atkLow, defLow) per nature: cumulative thresholds. */
  private val STYLES =
      arrayOf(
          intArrayOf(61, 7, 61, 7), // HARDY
          intArrayOf(20, 25, 84, 8), // LONELY
          intArrayOf(70, 15, 32, 60), // BRAVE
          intArrayOf(38, 31, 70, 15), // ADAMANT
          intArrayOf(20, 70, 70, 22), // NAUGHTY
          intArrayOf(30, 20, 32, 58), // BOLD
          intArrayOf(56, 22, 56, 22), // DOCILE
          intArrayOf(25, 15, 75, 15), // RELAXED
          intArrayOf(69, 6, 28, 55), // IMPISH
          intArrayOf(35, 10, 29, 6), // LAX
          intArrayOf(62, 10, 30, 20), // TIMID
          intArrayOf(58, 37, 88, 6), // HASTY
          intArrayOf(34, 11, 29, 11), // SERIOUS
          intArrayOf(35, 5, 35, 60), // JOLLY
          intArrayOf(56, 22, 56, 22), // NAIVE
          intArrayOf(35, 45, 34, 60), // MODEST
          intArrayOf(44, 50, 34, 6), // MILD
          intArrayOf(56, 22, 56, 22), // QUIET
          intArrayOf(30, 58, 30, 58), // BASHFUL
          intArrayOf(30, 13, 27, 6), // RASH
          intArrayOf(40, 50, 25, 62), // CALM
          intArrayOf(18, 70, 90, 5), // GENTLE
          intArrayOf(88, 6, 22, 20), // SASSY
          intArrayOf(42, 50, 42, 5), // CAREFUL
          intArrayOf(56, 22, 56, 22), // QUIRKY
      )

  /** sBattlePalaceNatureToFlavorTextId: 0 glint, 1 getting in position, 2 growling, 3 eager. */
  private val FLAVOR =
      intArrayOf(3, 0, 1, 0, 0, 1, 3, 0, 1, 2, 2, 0, 3, 1, 3, 1, 2, 3, 3, 2, 1, 0, 2, 2, 3)

  /** gBattlePalaceNatureToMoveGroupLikelihood as stored: {atk, atk+def, atkLow, atkLow+defLow}. */
  fun likelihood(nature: Int): IntArray {
    val s = STYLES[nature]
    return intArrayOf(s[0], s[0] + s[1], s[2], s[2] + s[3])
  }

  /** The group a nature picks for a roll [percent] (0-99), [low] = at or below half hp. */
  fun groupFor(nature: Int, percent: Int, low: Boolean): PalaceGroup {
    val t = likelihood(nature)
    val base = if (low) 2 else 0
    for (i in base until base + 2) if (t[i] > percent) return PalaceGroup.entries[i - base]
    return PalaceGroup.SUPPORT
  }

  /** GetBattlePalaceMoveGroup. */
  fun groupOf(move: MoveDef): PalaceGroup =
      when (move.target) {
        MoveTarget.SELECTED,
        MoveTarget.USER_OR_SELECTED,
        MoveTarget.RANDOM,
        MoveTarget.BOTH,
        MoveTarget.FOES_AND_ALLY -> if (move.power == 0) PalaceGroup.SUPPORT else PalaceGroup.ATTACK
        MoveTarget.DEPENDS,
        MoveTarget.OPPONENTS_FIELD -> PalaceGroup.SUPPORT
        MoveTarget.USER -> PalaceGroup.DEFENSE
      }

  fun flavorLine(nature: Int, name: String): String =
      when (FLAVOR[nature]) {
        0 -> "A glint appears in $name's eyes!"
        1 -> "$name is getting into position!"
        2 -> "$name began growling deeply!"
        else -> "$name is eager for more!"
      }

  /** The result of the selection: a move id, or null when the monster can't act. */
  data class Pick(val moveId: Int?, val fromNatureGroup: Boolean)

  /**
   * The selection without the AI step: [moves] are (move, has pp) per slot. Returns the moves of
   * the nature-picked group (for the AI to choose from), or the fallback pick.
   */
  fun select(
      nature: Int,
      low: Boolean,
      moves: List<Pair<MoveDef, Boolean>>,
      rng: Random,
  ): Pair<List<MoveDef>, Pick?> {
    val group = groupFor(nature, rng.nextInt(100), low)
    val inGroup = moves.filter { it.second && groupOf(it.first) == group }.map { it.first }
    if (inGroup.isNotEmpty()) return inGroup to null
    val usable = moves.filter { it.second }.map { it.first }
    if (usable.isEmpty()) return emptyList<MoveDef>() to Pick(null, false)
    val counts = PalaceGroup.entries.associateWith { g -> usable.count { groupOf(it) == g } }
    // The decomp's Support check reads the Defense total by mistake (never true), see BUGFIX.
    var multi = 0
    if (counts.getValue(PalaceGroup.ATTACK) >= 2) multi++
    if (counts.getValue(PalaceGroup.DEFENSE) >= 2) multi++
    val pick =
        if (multi != 1) usable[rng.nextInt(usable.size)]
        else {
          val g =
              if (counts.getValue(PalaceGroup.DEFENSE) >= 2) PalaceGroup.DEFENSE
              else PalaceGroup.ATTACK
          usable.filter { groupOf(it) == g }.let { it[rng.nextInt(it.size)] }
        }
    // Picked outside the nature's group: half the time it can't use it after all.
    return emptyList<MoveDef>() to Pick(if (rng.nextInt(100) >= 50) null else pick.id, false)
  }
}

/** The Battle Palace as [FacilityHooks]. */
class PalaceHooks(private val rng: Random = Random.Default) : FacilityHooks {
  /** gBattleStruct->palaceFlags: monsters already at or below half hp (flavor line shown). */
  private val lowFlags = mutableSetOf<Long>()
  private val seen = mutableSetOf<Long>()

  override val autoPlay: Boolean = true
  override val allowSwitch: Boolean = false

  private fun low(m: BattleMonState) = m.maxHp / 2 >= m.currentHp && m.currentHp > 0 && !m.asleep

  override fun beforeTurn(engine: TurnEngine, battle: BattleInstance) {
    // A monster that comes in already at or below half hp sets its flag without a line.
    for (m in battle.activeMons().filterNotNull() + battle.opponentMons().filterNotNull()) {
      if (seen.add(m.entityId) && low(m)) lowFlags += m.entityId
    }
  }

  override fun autoChoice(
      engine: TurnEngine,
      battle: BattleInstance,
      ref: BattlerRef,
  ): Pair<BattleChoice, String?>? {
    val ctx = engine.context(battle)
    val mon = ctx.live(ref) ?: return null
    // Two-turn and rampage moves carry on by themselves.
    val locked =
        mon.volatile.chargingMove.takeIf { it != 0 }
            ?: mon.volatile.rampageMove.takeIf { it != 0 }
            ?: mon.moves.firstOrNull()?.id?.toInt()?.takeIf { mon.volatile.mustRecharge }
    if (locked != null) return BattleChoice.UseMove(ref.side, ref.pos, locked) to null
    val moves =
        mon.moves.mapNotNull { slot ->
          val def = ctx.move(slot.id.toInt()) ?: return@mapNotNull null
          def to (slot.pp > 0 && def.id != mon.volatile.disabledMove)
        }
    val (group, fallback) = PalaceRules.select(mon.nature(), mon.entityId in lowFlags, moves, rng)
    if (fallback == null) {
      val choice = BattleAi.choose(ctx, ref, group.map { it.id }.toSet(), forceSmart = true)
      return choice to null
    }
    val id =
        fallback.moveId
            ?: return BattleChoice.Pass(ref.side, ref.pos) to
                "${BattleNames.token(mon)} appears incapable of using its power!"
    return BattleChoice.UseMove(ref.side, ref.pos, id) to null
  }

  override fun afterTurn(
      engine: TurnEngine,
      battle: BattleInstance,
      events: List<BattleEvent>,
  ): List<BattleEvent> {
    val out = mutableListOf<BattleEvent>()
    for (m in battle.activeMons().filterNotNull() + battle.opponentMons().filterNotNull()) {
      if (m.entityId in lowFlags || !low(m)) continue
      lowFlags += m.entityId
      out += BattleEvent.Message(PalaceRules.flavorLine(m.nature(), BattleNames.token(m)))
    }
    return out
  }
}

private fun BattleMonState.nature(): Int =
    ((source.seed.toLong() and 0xFFFFFFFFL) % 25).toInt()

/**
 * The Battle Arena (and Fallarbor Battle Tent): battle_arena.c. Monsters fight one on one in party
 * order with no switching; after three turns with both still standing the referee judges Mind
 * (sMindRatings of the moves used), Skill (hits that worked minus misses and ineffective ones) and
 * Body (hp left against the hp at the start of the match-up). Each category won is 2 points, a
 * tie 1; the lower total faints, a draw faints both.
 */
object ArenaRules {
  /** BattleArena_AddSkillPoints for one move's outcome. */
  fun skillFor(outcome: MoveOutcome): Int =
      when (outcome) {
        MoveOutcome.FAILED -> -2
        MoveOutcome.NO_EFFECT -> -2
        MoveOutcome.ABILITY_BLOCKED -> -3
        MoveOutcome.SUPER_EFFECTIVE -> 2
        MoveOutcome.NOT_VERY_EFFECTIVE -> -1
        MoveOutcome.PROTECTED -> 0
        MoveOutcome.NORMAL -> 1
      }

  enum class MoveOutcome {
    FAILED,
    NO_EFFECT,
    ABILITY_BLOCKED,
    SUPER_EFFECTIVE,
    NOT_VERY_EFFECTIVE,
    PROTECTED,
    NORMAL,
  }

  /** One category: 2 to the higher, 1 each on a tie. Returns (player points, opponent points). */
  fun category(player: Int, opponent: Int): Pair<Int, Int> =
      when {
        player > opponent -> 2 to 0
        player < opponent -> 0 to 2
        else -> 1 to 1
      }

  data class Judgment(
      val mind: Pair<Int, Int>,
      val skill: Pair<Int, Int>,
      val body: Pair<Int, Int>,
  ) {
    val player: Int
      get() = mind.first + skill.first + body.first

    val opponent: Int
      get() = mind.second + skill.second + body.second
  }

  fun judge(
      mind: Pair<Int, Int>,
      skill: Pair<Int, Int>,
      hp: Pair<Int, Int>,
      startHp: Pair<Int, Int>,
  ): Judgment {
    val body =
        (hp.first * 100 / startHp.first.coerceAtLeast(1)) to
            (hp.second * 100 / startHp.second.coerceAtLeast(1))
    return Judgment(
        category(mind.first, mind.second),
        category(skill.first, skill.second),
        category(body.first, body.second))
  }

  /** Symbol per category for the judgment window: ○ won, △ tie, ✕ lost. */
  fun mark(p: Int): String =
      when (p) {
        2 -> "O"
        1 -> "^"
        else -> "X"
      }
}

/** The Battle Arena as [FacilityHooks]. */
class ArenaHooks(
    private val mindRatings: Map<Int, Int>,
    private val playerName: String,
    private val trainerName: String,
) : FacilityHooks {
  override val allowSwitch: Boolean = false

  private var pair: Pair<Long, Long>? = null
  private var turns = 0
  private val mind = intArrayOf(0, 0)
  private val skill = intArrayOf(0, 0)
  private val startHp = intArrayOf(1, 1)

  /** The last judgment, for the frontier UI. */
  var lastJudgment: ArenaRules.Judgment? = null
    private set

  override fun beforeTurn(engine: TurnEngine, battle: BattleInstance) {
    val me = battle.activeMons().firstOrNull() ?: return
    val foe = battle.opponentMons().firstOrNull() ?: return
    val now = me.entityId to foe.entityId
    if (now != pair) {
      // A new match-up: BattleArena_InitPoints.
      pair = now
      turns = 0
      mind.fill(0)
      skill.fill(0)
      startHp[0] = me.currentHp
      startHp[1] = foe.currentHp
    }
  }

  override fun afterTurn(
      engine: TurnEngine,
      battle: BattleInstance,
      events: List<BattleEvent>,
  ): List<BattleEvent> {
    val (meId, foeId) = pair ?: return emptyList()
    score(events, meId, foeId)
    turns++
    val me = battle.party.firstOrNull { it.entityId == meId } ?: return emptyList()
    val foe = battle.opponent.firstOrNull { it.entityId == foeId } ?: return emptyList()
    if (turns < 3 || me.fainted || foe.fainted) return emptyList()
    // BattleScript_ArenaDoJudgment.
    val j =
        ArenaRules.judge(
            mind[0] to mind[1],
            skill[0] to skill[1],
            me.currentHp to foe.currentHp,
            startHp[0] to startHp[1])
    lastJudgment = j
    val meName = BattleNames.token(me)
    val foeName = BattleNames.token(foe)
    val out = mutableListOf<BattleEvent>()
    out += BattleEvent.Message("REFEREE: That's it! We will now go to judging to determine the winner!")
    out += BattleEvent.Message("REFEREE: Judging category 1, Mind! The POKéMON showing the most guts! (${ArenaRules.mark(j.mind.first)} - ${ArenaRules.mark(j.mind.second)})")
    out += BattleEvent.Message("REFEREE: Judging category 2, Skill! The POKéMON using moves the best! (${ArenaRules.mark(j.skill.first)} - ${ArenaRules.mark(j.skill.second)})")
    out += BattleEvent.Message("REFEREE: Judging category 3, Body! The POKéMON with the most vitality! (${ArenaRules.mark(j.body.first)} - ${ArenaRules.mark(j.body.second)})")
    val losers =
        when {
          j.player > j.opponent -> {
            out += BattleEvent.Message("REFEREE: Judgment: ${j.player} to ${j.opponent}! The winner is $playerName's $meName!")
            out += BattleEvent.Message("$meName defeated the opponent $foeName in a REFEREE's decision!")
            listOf(foe)
          }
          j.player < j.opponent -> {
            out += BattleEvent.Message("REFEREE: Judgment: ${j.player} to ${j.opponent}! The winner is $trainerName's $foeName!")
            out += BattleEvent.Message("$meName lost to the opponent $foeName in a REFEREE's decision!")
            listOf(me)
          }
          else -> {
            out += BattleEvent.Message("REFEREE: Judgment: 3 to 3! We have a draw!")
            out += BattleEvent.Message("$meName tied the opponent $foeName in a REFEREE's decision!")
            listOf(me, foe)
          }
        }
    for (m in losers) {
      m.currentHp = 0
      out += BattleEvent.HpChanged(m.entityId, 0, "judge")
      out += BattleEvent.Fainted(m.entityId)
      m.status = 0
    }
    return out
  }

  /** Mind and skill points of the turn's moves (BattleArena_AddMindPoints / AddSkillPoints). */
  private fun score(events: List<BattleEvent>, meId: Long, foeId: Long) {
    var i = 0
    while (i < events.size) {
      val e = events[i]
      if (e is BattleEvent.MoveUsed && (e.attackerId == meId || e.attackerId == foeId)) {
        val side = if (e.attackerId == meId) 0 else 1
        val target = if (side == 0) foeId else meId
        mind[side] += mindRatings[e.moveId.toInt()] ?: 0
        // The outcome: the events up to the next move.
        var j = i + 1
        var outcome: ArenaRules.MoveOutcome? = null
        var blocked = false
        while (j < events.size && events[j] !is BattleEvent.MoveUsed) {
          when (val t = events[j]) {
            is BattleEvent.MoveMissed -> outcome = outcome ?: ArenaRules.MoveOutcome.NO_EFFECT
            is BattleEvent.MoveFailed -> outcome = outcome ?: ArenaRules.MoveOutcome.FAILED
            is BattleEvent.AbilityShown -> if (t.holderId == target) blocked = true
            is BattleEvent.DamageDealt ->
                if (t.targetId == target && outcome == null)
                    outcome =
                        when {
                          t.effectiveness == 0 -> ArenaRules.MoveOutcome.NO_EFFECT
                          t.effectiveness > TypeChart.NEUTRAL -> ArenaRules.MoveOutcome.SUPER_EFFECTIVE
                          t.effectiveness < TypeChart.NEUTRAL -> ArenaRules.MoveOutcome.NOT_VERY_EFFECTIVE
                          else -> ArenaRules.MoveOutcome.NORMAL
                        }
            else -> Unit
          }
          j++
        }
        if (blocked && outcome != ArenaRules.MoveOutcome.SUPER_EFFECTIVE)
            outcome = ArenaRules.MoveOutcome.ABILITY_BLOCKED
        skill[side] += ArenaRules.skillFor(outcome ?: ArenaRules.MoveOutcome.NORMAL)
        i = j
        continue
      }
      i++
    }
  }
}
