package de.fiereu.openmmo.server.game.contest

/** Random() of the decomp: a 16-bit value per call. Tests script it. */
fun interface ContestRng {
  fun next(): Int
}

/**
 * gContestMons[i]: a contestant as the contest sees it (src/contest.c CreateContestMonFromParty).
 */
data class ContestMon(
    val dex: Int,
    val nickname: String,
    val trainerName: String,
    /** Up to four move ids, 0 for an empty slot. */
    val moves: List<Int>,
    val cool: Int,
    val beauty: Int,
    val cute: Int,
    val smart: Int,
    val tough: Int,
    val sheen: Int,
    /** 0 for a player's monster (no AI), the opponent's CONTEST_AI_* flags otherwise. */
    val aiFlags: Long = 0,
    /** The trainer's overworld sprite (OBJ_EVENT_GFX_* name), for the hall. */
    val trainerGfx: String = "",
    val isPlayer: Boolean = false,
    val shiny: Boolean = false,
) {
  fun condition(category: Int): Int =
      when (category) {
        Contest.COOL -> cool
        Contest.BEAUTY -> beauty
        Contest.CUTE -> cute
        Contest.SMART -> smart
        else -> tough
      }
}

/** eContestantStatus[i] (include/contest.h struct ContestantStatus). */
class ContestantStatus {
  var baseAppeal = 0
  var appeal = 0
  var pointTotal = 0
  var currMove = 0
  var prevMove = 0
  var moveCategory = 0
  var ranking = 0
  var moveRepeatCount = 0
  var noMoreTurns = false
  var nervous = false
  var numTurnsSkipped = 0
  var condition = 0
  var jam = 0
  var jamReduction = 0
  var resistant = false
  var immune = false
  var moreEasilyStartled = false
  var usedRepeatableMove = false
  var conditionMod = Contest.CONDITION_NO_CHANGE
  var turnOrderMod = 0
  var turnOrderModAction = 0
  var turnSkipped = false
  var exploded = false
  var overrideCategoryExcitementMod = false
  var appealTripleCondition = false
  var jamSafetyCount = 0
  var effectStringId = ContestString.NONE
  var effectStringId2 = ContestString.NONE
  var repeatedMove = false
  var repeatedPrevMove = false
  var completedComboFlag = false
  var hasJudgesAttention = false
  var judgesAttentionWasRemoved = false
  var usedComboMove = false
  var completedCombo = 0
  var comboAppealBonus = 0
  var repeatJam = 0
  var nextTurnOrder = Contest.NONE
  var attentionLevel = 0
  var contestantAnimTarget = 0
}

/** What the contest screen shows, in order (the APPEALSTATE_* steps of Task_DoAppeals). */
data class ContestEvent(
    val kind: String,
    val who: Int = -1,
    val text: String = "",
    /** hearts: the appeal before; crowd: the applause level after; judge: the symbol. */
    val a: Int = 0,
    /** hearts: the change. */
    val b: Int = 0,
    val move: Int = 0,
)

/** Judge speech bubble symbols (DoJudgeSpeechBubble). */
object JudgeSymbol {
  const val SWIRL = 0
  const val ONE_EXCLAMATION = 2
  const val TWO_EXCLAMATIONS = 3
  const val NUMBER_ONE = 5
  const val NUMBER_FOUR = 6
  const val QUESTION_MARK = 7
  const val STAR = 8
}

/**
 * One Hoenn contest's appeal phase and final scoring, a port of src/contest.c
 * (CalculateRound1Points, SortContestants, CalculateAppealMoveImpact, Task_DoAppeals,
 * RankContestants, SetAttentionLevels, SetContestantStatusesForNextRound, ApplyNextTurnOrder,
 * CalculateFinalScores) and src/contest_effect.c (every gContestEffectFuncs entry). The NPC
 * opponents pick their moves with [ContestAi] (src/contest_ai.c). Human contestants pass their move
 * slot to [playRound].
 */
class ContestEngine(
    val category: Int,
    val rank: Int,
    val mons: List<ContestMon>,
    private val rng: ContestRng,
    val data: ContestData = ContestData.instance,
    private val moveName: (Int) -> String = { "MOVE $it" },
) {
  init {
    require(mons.size == Contest.COUNT) { "a contest has ${Contest.COUNT} contestants" }
  }

  val status = List(Contest.COUNT) { ContestantStatus() }
  val round1Points = IntArray(Contest.COUNT) { round1(it) }

  /** gContestantTurnOrder / eContestAppealResults.turnOrder: contestant -> turn this round. */
  val turnOrder = IntArray(Contest.COUNT)
  var appealNumber = 0
    private set

  var applauseLevel = 0
    private set

  private var excitementFrozen = false
  private var excitementFreezer = 0
  private var moveExcitement = 0
  private var excitementAppealBonus = 0
  private var currentAppealer = 0
  private val jamQueue = mutableListOf<Int>()
  private var jam = 0
  private var jam2 = 0
  val moveHistory = Array(Contest.NUM_APPEALS) { IntArray(Contest.COUNT) }
  val excitementHistory = Array(Contest.NUM_APPEALS) { IntArray(Contest.COUNT) }
  val finalStandings = IntArray(Contest.COUNT)
  val round2Points = IntArray(Contest.COUNT)
  val totalPoints = IntArray(Contest.COUNT)
  var finished = false
    private set

  init {
    // InitContestResources: SortContestants(FALSE), then ApplyNextTurnOrder with every
    // nextTurnOrder unset.
    sortContestants(useRanking = false)
    applyNextTurnOrder()
  }

  private fun random(): Int = rng.next() and 0xFFFF

  // ---------------------------------------------------------------- round 1 (conditions)

  /** CalculateContestantRound1Points. */
  private fun round1(who: Int): Int {
    val m = mons[who]
    val (main, sub1, sub2) =
        when (category) {
          Contest.COOL -> Triple(m.cool, m.tough, m.beauty)
          Contest.BEAUTY -> Triple(m.beauty, m.cool, m.cute)
          Contest.CUTE -> Triple(m.cute, m.beauty, m.smart)
          Contest.SMART -> Triple(m.smart, m.cute, m.tough)
          else -> Triple(m.tough, m.smart, m.cool)
        }
    return (main and 0xFF) + ((sub1 and 0xFF) + (sub2 and 0xFF) + (m.sheen and 0xFF)) / 2
  }

  /** SortContestants. */
  private fun sortContestants(useRanking: Boolean) {
    val random = IntArray(Contest.COUNT)
    var i = 0
    while (i < Contest.COUNT) {
      random[i] = random()
      var unique = true
      for (j in 0 until i) if (random[i] == random[j]) unique = false
      if (unique) i++
    }
    if (!useRanking) {
      val order = IntArray(Contest.COUNT)
      for (c in 0 until Contest.COUNT) {
        order[c] = c
        var v = 0
        while (v < c) {
          val o = order[v]
          if (round1Points[o] < round1Points[c] ||
              (round1Points[o] == round1Points[c] && random[o] < random[c])) {
            for (j in c downTo v + 1) order[j] = order[j - 1]
            order[v] = c
            break
          }
          v++
        }
        if (v == c) order[c] = c
      }
      for (t in 0 until Contest.COUNT) turnOrder[order[t]] = t
    } else {
      val scratch = IntArray(Contest.COUNT) { Contest.NONE }
      for (c in 0 until Contest.COUNT) {
        var j = status[c].ranking
        while (true) {
          if (scratch[j] == Contest.NONE) {
            scratch[j] = c
            turnOrder[c] = j
            break
          }
          j++
        }
      }
      for (a in 0 until Contest.COUNT - 1) {
        for (v in Contest.COUNT - 1 downTo a + 1) {
          if (status[v - 1].ranking == status[v].ranking &&
              turnOrder[v - 1] < turnOrder[v] &&
              random[v - 1] < random[v]) {
            val t = turnOrder[v]
            turnOrder[v] = turnOrder[v - 1]
            turnOrder[v - 1] = t
          }
        }
      }
    }
  }

  /** ApplyNextTurnOrder. */
  private fun applyNextTurnOrder() {
    val newOrder = turnOrder.copyOf()
    val ordered = BooleanArray(Contest.COUNT)
    for (t in 0 until Contest.COUNT) {
      var j = 0
      while (j < Contest.COUNT) {
        if (status[j].nextTurnOrder == t) {
          newOrder[j] = t
          ordered[j] = true
          break
        }
        j++
      }
      if (j == Contest.COUNT) {
        var next = 0
        j = 0
        while (j < Contest.COUNT) {
          if (!ordered[j] && status[j].nextTurnOrder == Contest.NONE) {
            next = j
            j++
            break
          }
          j++
        }
        while (j < Contest.COUNT) {
          if (!ordered[j] &&
              status[j].nextTurnOrder == Contest.NONE &&
              turnOrder[next] > turnOrder[j])
              next = j
          j++
        }
        newOrder[next] = t
        ordered[next] = true
      }
    }
    for (c in 0 until Contest.COUNT) {
      turnOrder[c] = newOrder[c]
      status[c].nextTurnOrder = Contest.NONE
      status[c].turnOrderMod = 0
    }
  }

  // ---------------------------------------------------------------- helpers

  fun isTurnDisabled(c: Int) = status[c].numTurnsSkipped != 0 || status[c].noMoreTurns

  fun moveExcitementOf(move: Int): Int = data.excitement[category][data.move(move).category]

  /** AreMovesContestCombo. */
  fun areMovesCombo(last: Int, next: Int): Int {
    val starter = data.move(last).comboStarter
    if (starter == 0) return 0
    return if (starter in data.move(next).comboMoves &&
        data.comboStarterLookup.getOrElse(starter) { false })
        1
    else 0
  }

  fun allowedToCombo(c: Int) = !(status[c].repeatedMove || status[c].nervous)

  private fun setString(c: Int, id: Int) {
    status[c].effectStringId = id
  }

  private fun setString2(c: Int, id: Int) {
    status[c].effectStringId2 = id
  }

  private fun setStartledString(c: Int, jam: Int) {
    when {
      jam >= 60 -> setString(c, ContestString.TRIPPED_OVER)
      jam >= 40 -> setString(c, ContestString.LEAPT_UP)
      jam >= 30 -> setString(c, ContestString.UTTER_CRY)
      jam >= 20 -> setString(c, ContestString.TURNED_BACK)
      jam >= 10 -> setString(c, ContestString.LOOKED_DOWN)
    }
  }

  private fun makeNervous(c: Int) {
    status[c].nervous = true
    status[c].currMove = 0
  }

  // ---------------------------------------------------------------- move impact

  /** CalculateAppealMoveImpact. */
  private fun calculateAppealMoveImpact(c: Int) {
    val s = status[c]
    s.appeal = 0
    s.baseAppeal = 0
    if (isTurnDisabled(c)) return
    val move = s.currMove
    val effect = data.move(move).effect
    s.moveCategory = data.move(move).category
    if (s.currMove == s.prevMove && s.currMove != 0) {
      s.repeatedMove = true
      s.moveRepeatCount = (s.moveRepeatCount + 1) and 7
    } else {
      s.moveRepeatCount = 0
    }
    s.baseAppeal = data.effects[effect].appeal
    s.appeal = s.baseAppeal
    jam = data.effects[effect].jam
    jam2 = jam
    currentAppealer = c
    for (i in 0 until Contest.COUNT) status[i].jam = 0
    if (s.hasJudgesAttention && areMovesCombo(s.prevMove, s.currMove) == 0)
        s.hasJudgesAttention = false

    runEffect(effect)

    when {
      s.conditionMod == Contest.CONDITION_GAIN -> s.appeal += s.condition - 10
      s.appealTripleCondition -> s.appeal += s.condition * 3
      else -> s.appeal += s.condition
    }
    s.completedCombo = 0
    s.usedComboMove = false
    if (allowedToCombo(c)) {
      val combo = areMovesCombo(s.prevMove, s.currMove)
      if (combo != 0 && s.hasJudgesAttention) {
        s.completedCombo = combo
        s.usedComboMove = true
        s.hasJudgesAttention = false
        s.comboAppealBonus = (s.baseAppeal * s.completedCombo) and 0xFF
        s.completedComboFlag = true
      } else if (data.move(s.currMove).comboStarter != 0) {
        s.hasJudgesAttention = true
        s.usedComboMove = true
      } else {
        s.hasJudgesAttention = false
      }
    }
    if (s.repeatedMove) s.repeatJam = ((s.moveRepeatCount + 1) * 10) and 0xFF
    if (s.nervous) {
      s.hasJudgesAttention = false
      s.appeal = 0
      s.baseAppeal = 0
    }
    moveExcitement = moveExcitementOf(s.currMove)
    if (s.overrideCategoryExcitementMod) moveExcitement = 1
    excitementAppealBonus =
        if (moveExcitement > 0) {
          if (applauseLevel + moveExcitement > 4) 60 else 10
        } else 0
    // Transform / Role Play animation target: an rng draw the decomp also makes.
    var rnd = random() % (Contest.COUNT - 1)
    var i = 0
    while (i < Contest.COUNT) {
      if (i != c) {
        if (rnd == 0) break
        rnd--
      }
      i++
    }
    s.contestantAnimTarget = i
  }

  private fun runEffect(effect: Int) {
    val c = currentAppealer
    val me = status[c]
    when (effect) {
      0 -> {} // HIGHLY_APPEALING
      1 -> {
        me.moreEasilyStartled = true
        setString(c, ContestString.MORE_CONSCIOUS)
      }
      2 -> {
        me.exploded = true
        setString(c, ContestString.NO_APPEAL)
      }
      3 -> {
        me.usedRepeatableMove = true
        me.repeatedMove = false
        me.moveRepeatCount = 0
      }
      4 -> {
        me.jamSafetyCount = 1
        setString(c, ContestString.SETTLE_DOWN)
      }
      5 -> {
        me.immune = true
        setString(c, ContestString.OBLIVIOUS_TO_OTHERS)
      }
      6 -> {
        me.jamReduction = 20
        setString(c, ContestString.LESS_AWARE)
      }
      7 -> {
        me.resistant = true
        setString(c, ContestString.STOPPED_CARING)
      }
      8,
      10,
      12 -> startleFrontMon()
      9,
      11,
      13 -> startlePrevMons()
      14 -> {
        val r = random() % 10
        jam = if (r < 2) 20 else if (r < 8) 40 else 60
        startleFrontMon()
      }
      15 -> startlePrevMons2()
      16 -> shiftJudgeAttention()
      17 -> startleMonWithJudgesAttention()
      18 -> {
        me.turnSkipped = true
        startlePrevMons()
        setString(c, ContestString.ATTEMPT_STARTLE)
      }
      19 -> {
        jamByMoveCategory(data.move(me.currMove).category)
        setString(c, ContestString.ATTEMPT_STARTLE)
      }
      20,
      21,
      22,
      23,
      24 -> {
        jamByMoveCategory(effect - 20)
        setString(c, ContestString.ATTEMPT_STARTLE)
      }
      25 -> makeFollowingMonNervous()
      26 -> makeFollowingMonsNervous()
      27 -> worsenConditionOfPrevMons()
      28 -> badlyStartlesMonsInGoodCondition()
      29 ->
          if (turnOrder[c] == 0) {
            me.appeal += 2 * data.effectOf(me.currMove).appeal
            setString(c, ContestString.HUSTLE_STANDOUT)
          }
      30 ->
          if (turnOrder[c] == 3) {
            me.appeal += 2 * data.effectOf(me.currMove).appeal
            setString(c, ContestString.WORK_HARD_UNNOTICED)
          }
      31 -> appealAsGoodAsPrevOnes()
      32 -> appealAsGoodAsPrevOne()
      33 -> betterWhenLater()
      34 -> qualityDependsOnTiming()
      35 -> betterIfSameType()
      36 -> betterIfDiffType()
      37 -> affectedByPrevAppeal()
      38 ->
          if (me.condition < 30) {
            me.condition += 10
            me.conditionMod = Contest.CONDITION_GAIN
            setString(c, ContestString.CONDITION_ROSE)
          } else setString(c, ContestString.NO_CONDITION_IMPROVE)
      39 -> {
        me.appealTripleCondition = true
        setString(
            c,
            if (me.condition != 0) ContestString.HOT_STATUS
            else ContestString.BAD_CONDITION_WEAK_APPEAL)
      }
      40 -> nextAppealEarlier()
      41 -> nextAppealLater()
      42 -> {} // MAKE_SCRAMBLING_TURN_ORDER_EASIER, dummied out
      43 -> scrambleNextTurnOrder()
      44 -> if (data.move(me.currMove).category != category) me.overrideCategoryExcitementMod = true
      45 -> badlyStartleMonsWithGoodAppeals()
      46 -> betterWhenAudienceExcited()
      47 ->
          if (!excitementFrozen) {
            excitementFrozen = true
            excitementFreezer = c
            setString(c, ContestString.ATTRACTED_ATTENTION)
          }
    }
  }

  private fun startleFrontMon() {
    val a = currentAppealer
    var hit = false
    if (turnOrder[a] != 0) {
      var i = 0
      while (i < Contest.COUNT) {
        if (turnOrder[a] - 1 == turnOrder[i]) break
        i++
      }
      jamQueue.clear()
      jamQueue += i
      hit = wasAtLeastOneOpponentJammed()
    }
    if (!hit) setString2(a, ContestString.MESSED_UP2)
    setString(a, ContestString.ATTEMPT_STARTLE)
  }

  private fun startlePrevMons() {
    val a = currentAppealer
    var hit = false
    if (turnOrder[a] != 0) {
      jamQueue.clear()
      for (i in 0 until Contest.COUNT) if (turnOrder[a] > turnOrder[i]) jamQueue += i
      hit = wasAtLeastOneOpponentJammed()
    }
    if (!hit) setString2(a, ContestString.MESSED_UP2)
    setString(a, ContestString.ATTEMPT_STARTLE)
  }

  private fun startlePrevMons2() {
    val a = currentAppealer
    var startled = 0
    if (turnOrder[a] != 0) {
      for (i in 0 until Contest.COUNT) {
        if (turnOrder[a] > turnOrder[i]) {
          jamQueue.clear()
          jamQueue += i
          val r = random() % 10
          jam =
              when {
                r == 0 -> 0
                r <= 2 -> 10
                r <= 4 -> 20
                r <= 6 -> 30
                r <= 8 -> 40
                else -> 60
              }
          if (wasAtLeastOneOpponentJammed()) startled++
        }
      }
    }
    setString(a, ContestString.ATTEMPT_STARTLE)
    if (startled == 0) setString2(a, ContestString.MESSED_UP2)
  }

  private fun shiftJudgeAttention() {
    val a = currentAppealer
    var hit = false
    if (turnOrder[a] != 0) {
      for (i in 0 until Contest.COUNT) {
        if (turnOrder[a] > turnOrder[i] && status[i].hasJudgesAttention && canUnnerve(i)) {
          status[i].hasJudgesAttention = false
          status[i].judgesAttentionWasRemoved = true
          setString(i, ContestString.JUDGE_LOOK_AWAY2)
          hit = true
        }
      }
    }
    setString(a, ContestString.DAZZLE_ATTEMPT)
    if (!hit) setString2(a, ContestString.MESSED_UP2)
  }

  private fun startleMonWithJudgesAttention() {
    val a = currentAppealer
    var startled = 0
    if (turnOrder[a] != 0) {
      for (i in 0 until Contest.COUNT) {
        if (turnOrder[a] > turnOrder[i]) {
          jam = if (status[i].hasJudgesAttention) 50 else 10
          jamQueue.clear()
          jamQueue += i
          if (wasAtLeastOneOpponentJammed()) startled++
        }
      }
    }
    setString(a, ContestString.ATTEMPT_STARTLE)
    if (startled == 0) setString2(a, ContestString.MESSED_UP2)
  }

  private fun jamByMoveCategory(cat: Int) {
    val a = currentAppealer
    var jammed = 0
    for (i in 0 until Contest.COUNT) {
      if (turnOrder[a] > turnOrder[i]) {
        jam = if (cat == data.move(status[i].currMove).category) 40 else 10
        jamQueue.clear()
        jamQueue += i
        if (wasAtLeastOneOpponentJammed()) jammed++
      }
    }
    if (jammed == 0) setString2(a, ContestString.MESSED_UP2)
  }

  private fun makeFollowingMonNervous() {
    val a = currentAppealer
    var hit = false
    if (turnOrder[a] != 3) {
      for (i in 0 until Contest.COUNT) {
        if (turnOrder[a] + 1 == turnOrder[i]) {
          if (canUnnerve(i)) {
            makeNervous(i)
            setString(i, ContestString.NERVOUS)
          } else setString(i, ContestString.UNAFFECTED)
          hit = true
        }
      }
    }
    setString(a, ContestString.UNNERVE_ATTEMPT)
    if (!hit) setString2(a, ContestString.MESSED_UP2)
  }

  private fun makeFollowingMonsNervous() {
    val a = currentAppealer
    var unnerved = 0
    val ids = mutableListOf<Int>()
    for (i in 0 until Contest.COUNT) {
      if (turnOrder[a] < turnOrder[i] && !status[i].nervous && !isTurnDisabled(i)) ids += i
    }
    val odds =
        when (ids.size) {
          1 -> intArrayOf(60, 0, 0, 0)
          2 -> intArrayOf(30, 30, 0, 0)
          3 -> intArrayOf(20, 20, 20, 0)
          else -> IntArray(4)
        }
    val oddsMod = IntArray(Contest.COUNT)
    for (i in 0 until Contest.COUNT) {
      oddsMod[i] =
          if (status[i].hasJudgesAttention && allowedToCombo(i))
              (if (data.comboStarterLookup.getOrElse(data.move(status[i].prevMove).comboStarter) {
                false
              })
                  1
              else 0) * 10
          else 0
      oddsMod[i] -= (status[i].condition / 10) * 10
    }
    if (odds[0] != 0) {
      for ((k, id) in ids.withIndex()) {
        var unaffected = false
        if (random() % 100 < odds[k] + oddsMod[id]) {
          if (canUnnerve(id)) {
            makeNervous(id)
            setString(id, ContestString.NERVOUS)
            unnerved++
          } else unaffected = true
        } else unaffected = true
        if (unaffected) {
          setString(id, ContestString.UNAFFECTED)
          unnerved++
        }
      }
    }
    setString(a, ContestString.UNNERVE_WAITING)
    if (unnerved == 0) setString2(a, ContestString.MESSED_UP2)
  }

  private fun worsenConditionOfPrevMons() {
    val a = currentAppealer
    var hit = 0
    for (i in 0 until Contest.COUNT) {
      if (turnOrder[a] > turnOrder[i] && status[i].condition > 0 && canUnnerve(i)) {
        status[i].condition = 0
        status[i].conditionMod = Contest.CONDITION_LOSE
        setString(i, ContestString.REGAINED_FORM)
        hit++
      }
    }
    setString(a, ContestString.TAUNT_WELL)
    if (hit == 0) setString2(a, ContestString.IGNORED)
  }

  private fun badlyStartlesMonsInGoodCondition() {
    val a = currentAppealer
    var hit = 0
    for (i in 0 until Contest.COUNT) {
      if (turnOrder[a] > turnOrder[i]) {
        jam = if (status[i].condition > 0) 40 else 10
        jamQueue.clear()
        jamQueue += i
        if (wasAtLeastOneOpponentJammed()) hit++
      }
    }
    setString(a, ContestString.JAM_WELL)
    if (hit == 0) setString2(a, ContestString.IGNORED)
  }

  private fun appealAsGoodAsPrevOnes() {
    val a = currentAppealer
    var sum = 0
    for (i in 0 until Contest.COUNT) if (turnOrder[a] > turnOrder[i]) sum += status[i].appeal
    if (sum < 0) sum = 0
    if (turnOrder[a] == 0 || sum == 0) setString(a, ContestString.APPEAL_NOT_WELL)
    else {
      status[a].appeal += sum / 2
      setString(a, ContestString.WORK_BEFORE)
    }
    status[a].appeal = roundTowardsZero(status[a].appeal)
  }

  private fun appealAsGoodAsPrevOne() {
    val a = currentAppealer
    var appeal = 0
    if (turnOrder[a] != 0)
        for (i in 0 until Contest.COUNT) if (turnOrder[a] - 1 == turnOrder[i])
            appeal = status[i].appeal
    if (turnOrder[a] == 0 || appeal <= 0) setString(a, ContestString.APPEAL_NOT_WELL2)
    else {
      status[a].appeal += appeal
      setString(a, ContestString.WORK_PRECEDING)
    }
  }

  private fun betterWhenLater() {
    val a = currentAppealer
    val turn = turnOrder[a]
    status[a].appeal = if (turn == 0) 10 else 20 * turn
    setString(
        a,
        when (turn) {
          0 -> ContestString.APPEAL_NOT_SHOWN_WELL
          1 -> ContestString.APPEAL_SLIGHTLY_WELL
          2 -> ContestString.APPEAL_PRETTY_WELL
          else -> ContestString.APPEAL_EXCELLENTLY
        })
  }

  private fun qualityDependsOnTiming() {
    val a = currentAppealer
    val r = random() % 10
    val (appeal, s) =
        when {
          r < 3 -> 10 to ContestString.APPEAL_NOT_VERY_WELL
          r < 6 -> 20 to ContestString.APPEAL_SLIGHTLY_WELL2
          r < 8 -> 40 to ContestString.APPEAL_PRETTY_WELL2
          r < 9 -> 60 to ContestString.APPEAL_VERY_WELL
          else -> 80 to ContestString.APPEAL_EXCELLENTLY2
        }
    setString(a, s)
    status[a].appeal = appeal
  }

  private fun betterIfSameType() {
    val a = currentAppealer
    val turn = turnOrder[a]
    if (turn == 0) return
    var i = turn - 1
    var j: Int
    while (true) {
      j = 0
      while (j < Contest.COUNT) {
        if (turnOrder[j] == i) break
        j++
      }
      val sj = status[j]
      if (sj.noMoreTurns || sj.nervous || sj.numTurnsSkipped != 0) {
        if (--i < 0) return
      } else break
    }
    val move = status[a].currMove
    if (data.move(move).category == data.move(status[j].currMove).category) {
      status[a].appeal += data.effectOf(move).appeal * 2
      setString(a, ContestString.SAME_TYPE_GOOD)
    }
  }

  private fun betterIfDiffType() {
    val a = currentAppealer
    if (turnOrder[a] == 0) return
    val move = status[a].currMove
    for (i in 0 until Contest.COUNT) {
      if (turnOrder[a] - 1 == turnOrder[i] &&
          data.move(move).category != data.move(status[i].currMove).category) {
        status[a].appeal += data.effectOf(move).appeal * 2
        setString(a, ContestString.DIFF_TYPE_GOOD)
        break
      }
    }
  }

  private fun affectedByPrevAppeal() {
    val a = currentAppealer
    if (turnOrder[a] == 0) return
    for (i in 0 until Contest.COUNT) {
      if (turnOrder[a] - 1 == turnOrder[i]) {
        if (status[a].appeal > status[i].appeal) {
          status[a].appeal *= 2
          setString(a, ContestString.STOOD_OUT_AS_MUCH)
        } else if (status[a].appeal < status[i].appeal) {
          status[a].appeal = 0
          setString(a, ContestString.NOT_AS_WELL)
        }
      }
    }
  }

  private fun nextAppealEarlier() {
    val a = currentAppealer
    if (appealNumber == Contest.LAST_APPEAL) return
    val order = IntArray(Contest.COUNT) { status[it].nextTurnOrder }
    order[a] = Contest.NONE
    for (i in 0 until Contest.COUNT) {
      var j = 0
      while (j < Contest.COUNT) {
        if (j != a && i == order[j] && order[j] == status[j].nextTurnOrder) {
          order[j]++
          break
        }
        j++
      }
      if (j == Contest.COUNT) break
    }
    order[a] = 0
    status[a].turnOrderMod = 1
    for (i in 0 until Contest.COUNT) status[i].nextTurnOrder = order[i]
    status[a].turnOrderModAction = 1
    setString(a, ContestString.MOVE_UP_LINE)
  }

  private fun nextAppealLater() {
    val a = currentAppealer
    if (appealNumber == Contest.LAST_APPEAL) return
    val order = IntArray(Contest.COUNT) { status[it].nextTurnOrder }
    order[a] = Contest.NONE
    for (i in Contest.COUNT - 1 downTo 0) {
      var j = 0
      while (j < Contest.COUNT) {
        if (j != a && i == order[j] && order[j] == status[j].nextTurnOrder) {
          order[j]--
          break
        }
        j++
      }
      if (j == Contest.COUNT) break
    }
    order[a] = Contest.COUNT - 1
    status[a].turnOrderMod = 1
    for (i in 0 until Contest.COUNT) status[i].nextTurnOrder = order[i]
    status[a].turnOrderModAction = 2
    setString(a, ContestString.MOVE_BACK_LINE)
  }

  private fun scrambleNextTurnOrder() {
    val a = currentAppealer
    if (appealNumber == Contest.LAST_APPEAL) return
    val order = IntArray(Contest.COUNT) { status[it].nextTurnOrder }
    val unselected = IntArray(Contest.COUNT) { it }
    for (i in 0 until Contest.COUNT) {
      var r = random() % (Contest.COUNT - i)
      for (j in 0 until Contest.COUNT) {
        if (unselected[j] != Contest.NONE) {
          if (r == 0) {
            order[j] = i
            unselected[j] = Contest.NONE
            break
          } else r--
        }
      }
    }
    for (i in 0 until Contest.COUNT) {
      status[i].nextTurnOrder = order[i]
      status[i].turnOrderMod = 2
    }
    status[a].turnOrderModAction = 3
    setString(a, ContestString.SCRAMBLE_ORDER)
  }

  private fun badlyStartleMonsWithGoodAppeals() {
    val a = currentAppealer
    var jammed = 0
    for (i in 0 until Contest.COUNT) {
      if (turnOrder[a] > turnOrder[i]) {
        jam = if (status[i].appeal > 0) roundUp(status[i].appeal / 2) else 10
        jamQueue.clear()
        jamQueue += i
        if (wasAtLeastOneOpponentJammed()) jammed++
      }
    }
    if (jammed == 0) setString2(a, ContestString.MESSED_UP2)
    setString(a, ContestString.ATTEMPT_STARTLE)
  }

  private fun betterWhenAudienceExcited() {
    val a = currentAppealer
    val (appeal, s) =
        when (applauseLevel) {
          0 -> 10 to ContestString.APPEAL_NOT_VERY_WELL
          1 -> 20 to ContestString.APPEAL_SLIGHTLY_WELL2
          2 -> 30 to ContestString.APPEAL_PRETTY_WELL2
          3 -> 50 to ContestString.APPEAL_VERY_WELL
          else -> 60 to ContestString.APPEAL_EXCELLENTLY2
        }
    setString(a, s)
    status[a].appeal = appeal
  }

  /** CanUnnerveContestant. */
  private fun canUnnerve(i: Int): Boolean {
    val s = status[i]
    return when {
      s.immune -> {
        setString(i, ContestString.AVOID_SEEING)
        false
      }
      s.jamSafetyCount != 0 -> {
        s.jamSafetyCount--
        setString(i, ContestString.AVERT_GAZE)
        false
      }
      !s.noMoreTurns && s.numTurnsSkipped == 0 -> true
      else -> false
    }
  }

  /** WasAtLeastOneOpponentJammed. */
  private fun wasAtLeastOneOpponentJammed(): Boolean {
    val buffer = IntArray(Contest.COUNT)
    for (c in jamQueue) {
      if (canUnnerve(c)) {
        jam2 = jam
        if (status[c].moreEasilyStartled) jam2 *= 2
        if (status[c].resistant) {
          jam2 = 10
          setString(c, ContestString.LITTLE_DISTRACTED)
        } else {
          jam2 -= status[c].jamReduction
          if (jam2 <= 0) {
            jam2 = 0
            setString(c, ContestString.NOT_FAZED)
          } else {
            // JamContestant takes a u8 jam.
            val j = jam2 and 0xFF
            status[c].appeal -= j
            status[c].jam = (status[c].jam + j) and 0xFF
            setStartledString(c, jam2)
            buffer[c] = jam2
          }
        }
      }
    }
    return buffer.any { it != 0 }
  }

  private fun roundTowardsZero(score: Int): Int {
    val abs = Math.abs(score) % 10
    return if (score < 0) {
      if (abs != 0) score - (10 - abs) else score
    } else score - abs
  }

  private fun roundUp(score: Int): Int {
    val abs = Math.abs(score) % 10
    return if (abs != 0) score + 10 - abs else score
  }

  // ---------------------------------------------------------------- texts

  private fun expand(template: String, v1: String = "", v2: String = "", v3: String = ""): String =
      template
          .replace("{STR_VAR_1}", v1)
          .replace("{STR_VAR_2}", v2)
          .replace("{STR_VAR_3}", v3)
          .replace(Regex("\\{[A-Z_]+[^}]*\\}"), "")

  private fun misc(key: String) = data.misc[key].orEmpty()

  /** PrintAppealMoveResultText. */
  private fun resultText(c: Int, stringId: Int): String {
    val shyness =
        when (data.move(status[currentAppealer].currMove).category) {
          Contest.COOL -> misc("gText_Contest_Shyness")
          Contest.BEAUTY -> misc("gText_Contest_Anxiety")
          Contest.CUTE -> misc("gText_Contest_Laziness")
          Contest.SMART -> misc("gText_Contest_Hesitancy")
          else -> misc("gText_Contest_Fear")
        }
    return expand(
        data.appealResults.getOrElse(stringId) { "" },
        mons[c].nickname,
        moveName(status[c].currMove),
        shyness)
  }

  /** "Appeal no. N! Which move will be played?" for a contestant (Task_DisplayAppealNumberText). */
  fun appealPrompt(c: Int): String =
      expand(
          misc(
              if (!isTurnDisabled(c)) "gText_AppealNumWhichMoveWillBePlayed"
              else "gText_AppealNumButItCantParticipate"),
          (appealNumber + 1).toString())

  /** The round result text of a contestant (sRoundResultTexts[attentionLevel]). */
  fun roundResultText(c: Int): String =
      expand(data.roundResults.getOrElse(status[c].attentionLevel) { "" }, mons[c].nickname)

  // ---------------------------------------------------------------- a round

  /** The move each NPC picks this round (GetChosenMove with the contest AI). */
  fun aiChoice(c: Int): Int = ContestAi(this, c, rng).chooseMoveIndex()

  /**
   * One round of appeals. [humanChoice] gives a move slot (0-3) for each human contestant; NPCs
   * choose with the AI. Returns the events in the order the contest screen shows them.
   */
  fun playRound(humanChoice: Map<Int, Int>): List<ContestEvent> {
    check(!finished) { "the contest is over" }
    val ev = mutableListOf<ContestEvent>()
    // GetAllChosenMoves, before any appeal of the round.
    for (c in 0 until Contest.COUNT) {
      status[c].currMove =
          if (isTurnDisabled(c)) 0
          else {
            val slot =
                if (mons[c].aiFlags == 0L && c in humanChoice) humanChoice.getValue(c)
                else if (mons[c].aiFlags != 0L) aiChoice(c) else firstMoveSlot(c)
            mons[c].moves.getOrElse(slot.coerceIn(0, 3)) { 0 }.takeIf { it != 0 }
                ?: mons[c].moves.first { it != 0 }
          }
    }
    for (turn in 0 until Contest.COUNT) {
      val c = (0 until Contest.COUNT).first { turnOrder[it] == turn }
      appealTurn(c, ev)
    }
    // Task_FinishRoundOfAppeals
    rankContestants()
    setAttentionLevels()
    for (c in 0 until Contest.COUNT) ev += ContestEvent("round", c, roundResultText(c))
    ev += ContestEvent("order")
    // Task_ResetForNextRound
    setContestantStatusesForNextRound()
    appealNumber++
    if (appealNumber == Contest.NUM_APPEALS) {
      endAppeals()
      ev += ContestEvent("text", -1, expand(misc("gText_AllOutOfAppealTime")))
      ev += ContestEvent("end")
    }
    return ev
  }

  private fun firstMoveSlot(c: Int) = mons[c].moves.indexOfFirst { it != 0 }.coerceAtLeast(0)

  private fun appealTurn(c: Int, ev: MutableList<ContestEvent>) {
    calculateAppealMoveImpact(c)
    val s = status[c]
    val nick = mons[c].nickname
    if (s.numTurnsSkipped != 0 || s.noMoreTurns) {
      ev += ContestEvent("skip", c, expand(misc("gText_MonWasWatchingOthers"), nick))
      return
    }
    ev += ContestEvent("enter", c)
    if (s.nervous) {
      if (s.hasJudgesAttention) s.hasJudgesAttention = false
      ev +=
          ContestEvent(
              "text", c, expand(misc("gText_MonWasTooNervousToMove"), nick, moveName(s.currMove)))
      ev += ContestEvent("leave", c)
      return
    }
    ev +=
        ContestEvent(
            "appeal",
            c,
            expand(misc("gText_MonAppealedWithMove"), nick, moveName(s.currMove)),
            move = s.currMove)
    // APPEALSTATE_TRY_PRINT_MOVE_RESULT
    if (s.effectStringId != ContestString.NONE) {
      ev += ContestEvent("text", c, resultText(c, s.effectStringId))
      s.effectStringId = ContestString.NONE
    }
    if (s.effectStringId2 != ContestString.NONE) {
      val othersPending =
          (0 until Contest.COUNT).any { it != c && status[it].effectStringId != ContestString.NONE }
      if (!othersPending) {
        ev += ContestEvent("text", c, resultText(c, s.effectStringId2))
        s.effectStringId2 = ContestString.NONE
      }
    }
    when (s.turnOrderModAction) {
      1 -> ev += ContestEvent("judge", c, a = JudgeSymbol.NUMBER_ONE)
      2 -> ev += ContestEvent("judge", c, a = JudgeSymbol.NUMBER_FOUR)
      3 -> ev += ContestEvent("judge", c, a = JudgeSymbol.QUESTION_MARK)
    }
    ev += ContestEvent("hearts", c, a = 0, b = s.appeal)
    if (s.conditionMod == Contest.CONDITION_GAIN)
        ev += ContestEvent("judge", c, a = JudgeSymbol.STAR)
    updateStars(c, ev)
    ev += statusEvent(c)
    // APPEALSTATE_UPDATE_OPPONENT: in box (turn) order, every other contestant with a message.
    for (box in 0 until Contest.COUNT) {
      val j =
          (0 until Contest.COUNT).firstOrNull {
            it != c && turnOrder[it] == box && status[it].effectStringId != ContestString.NONE
          } ?: continue
      ev += ContestEvent("text", j, resultText(j, status[j].effectStringId))
      status[j].effectStringId = ContestString.NONE
      ev += ContestEvent("hearts", j, a = status[j].appeal + status[j].jam, b = -status[j].jam)
      updateStars(j, ev)
      ev += statusEvent(j)
      if (status[j].judgesAttentionWasRemoved) status[j].judgesAttentionWasRemoved = false
    }
    if (s.numTurnsSkipped != 0 || s.turnSkipped)
        ev += ContestEvent("text", c, expand(misc("gText_MonCantAppealNextTurn"), nick))
    if (s.usedComboMove) {
      if (s.completedCombo != 0) {
        ev += ContestEvent("text", c, expand(misc("gText_AppealComboWentOverWell")))
        ev += ContestEvent("judge", c, a = JudgeSymbol.TWO_EXCLAMATIONS)
      } else {
        ev += ContestEvent("text", c, expand(misc("gText_JudgeLookedAtMonExpectantly"), nick))
        ev += ContestEvent("judge", c, a = JudgeSymbol.ONE_EXCLAMATION)
      }
      if (!s.hasJudgesAttention) {
        ev += ContestEvent("hearts", c, a = s.appeal, b = s.comboAppealBonus)
        s.appeal += s.comboAppealBonus
      }
    }
    if (s.repeatedMove) {
      ev += ContestEvent("text", c, expand(misc("gText_RepeatedAppeal"), nick))
      ev += ContestEvent("judge", c, a = JudgeSymbol.SWIRL)
      ev += ContestEvent("hearts", c, a = s.appeal, b = -s.repeatJam)
      s.appeal -= s.repeatJam
    }
    // APPEALSTATE_UPDATE_CROWD
    if (excitementFrozen && c != excitementFreezer) {
      ev +=
          ContestEvent(
              "text",
              c,
              expand(
                  misc("gText_CrowdContinuesToWatchMon"),
                  nick,
                  moveName(s.currMove),
                  mons[excitementFreezer].nickname))
      ev +=
          ContestEvent(
              "text", c, expand(misc("gText_MonsMoveIsIgnored"), nick, moveName(s.currMove)))
    } else {
      var r = moveExcitement
      val v3: String
      if (s.overrideCategoryExcitementMod) {
        r = 1
        v3 = moveName(s.currMove)
      } else v3 = data.conditionNames.getOrElse(data.move(s.currMove).category) { "" }
      if (r > 0 && s.repeatedMove) r = 0
      applauseLevel = (applauseLevel + r).coerceAtLeast(0)
      if (r != 0) {
        val key =
            when {
              r < 0 -> "gText_MonsXDidntGoOverWell"
              applauseLevel <= 4 -> "gText_MonsXWentOverGreat"
              else -> "gText_MonsXGotTheCrowdGoing"
            }
        ev += ContestEvent("crowd", c, expand(misc(key), nick, "", v3), a = applauseLevel, b = r)
        if (r > 0) {
          ev += ContestEvent("hearts", c, a = s.appeal, b = excitementAppealBonus)
          s.appeal += excitementAppealBonus
        }
      }
    }
    // APPEALSTATE_WAIT_SLIDE_APPLAUSE: a full meter resets.
    if (applauseLevel > 4) {
      applauseLevel = 0
      ev += ContestEvent("crowd", c, a = 0, b = 0)
    }
    ev += ContestEvent("leave", c)
  }

  private fun updateStars(c: Int, ev: MutableList<ContestEvent>) {
    if (status[c].conditionMod == Contest.CONDITION_NO_CHANGE) return
    ev += ContestEvent("stars", c, a = status[c].condition / 10)
    status[c].conditionMod = Contest.CONDITION_NO_CHANGE
  }

  /** DrawStatusSymbol: which symbol the contestant's box shows. */
  fun statusSymbol(c: Int): String {
    val s = status[c]
    return when {
      s.resistant || s.immune || s.jamSafetyCount != 0 || s.jamReduction != 0 -> "shield"
      s.nervous -> "nervous"
      s.numTurnsSkipped != 0 || s.noMoreTurns -> "skip"
      s.hasJudgesAttention -> "attention"
      else -> ""
    }
  }

  private fun statusEvent(c: Int) = ContestEvent("status", c, statusSymbol(c))

  /** RankContestants. */
  private fun rankContestants() {
    val arr = IntArray(Contest.COUNT)
    for (i in 0 until Contest.COUNT) {
      status[i].pointTotal += status[i].appeal
      arr[i] = status[i].pointTotal
    }
    for (i in 0 until Contest.COUNT - 1) {
      for (j in Contest.COUNT - 1 downTo i + 1) {
        if (arr[j - 1] < arr[j]) {
          val t = arr[j]
          arr[j] = arr[j - 1]
          arr[j - 1] = t
        }
      }
    }
    for (i in 0 until Contest.COUNT) {
      for (j in 0 until Contest.COUNT) {
        if (status[i].pointTotal == arr[j]) {
          status[i].ranking = j
          break
        }
      }
    }
    sortContestants(useRanking = true)
    applyNextTurnOrder()
  }

  /** SetAttentionLevels. */
  private fun setAttentionLevels() {
    for (s in status) {
      s.attentionLevel =
          when {
            s.currMove == 0 -> 5
            s.appeal <= 0 -> 0
            s.appeal < 30 -> 1
            s.appeal < 60 -> 2
            s.appeal < 80 -> 3
            else -> 4
          }
    }
  }

  /** SetContestantStatusesForNextRound. */
  private fun setContestantStatusesForNextRound() {
    for (s in status) {
      s.appeal = 0
      s.baseAppeal = 0
      s.jamSafetyCount = 0
      if (s.numTurnsSkipped > 0) s.numTurnsSkipped--
      s.jam = 0
      s.resistant = false
      s.jamReduction = 0
      s.immune = false
      s.moreEasilyStartled = false
      s.usedRepeatableMove = false
      s.nervous = false
      s.effectStringId = ContestString.NONE
      s.effectStringId2 = ContestString.NONE
      s.conditionMod = Contest.CONDITION_NO_CHANGE
      s.repeatedPrevMove = s.repeatedMove
      s.repeatedMove = false
      s.turnOrderModAction = 0
      s.appealTripleCondition = false
      if (s.turnSkipped) {
        s.numTurnsSkipped = 1
        s.turnSkipped = false
      }
      if (s.exploded) {
        s.noMoreTurns = true
        s.exploded = false
      }
      s.overrideCategoryExcitementMod = false
    }
    for (i in 0 until Contest.COUNT) {
      status[i].prevMove = status[i].currMove
      moveHistory[appealNumber][i] = status[i].currMove
      excitementHistory[appealNumber][i] = moveExcitementOf(status[i].currMove)
      status[i].currMove = 0
    }
    excitementFrozen = false
  }

  /** Task_EndAppeals + CalculateFinalScores + DetermineFinalStandings. */
  private fun endAppeals() {
    for (i in 0 until Contest.COUNT) {
      round2Points[i] = status[i].pointTotal * 2
      totalPoints[i] = round1Points[i] + round2Points[i]
    }
    val random = IntArray(Contest.COUNT)
    var i = 0
    while (i < Contest.COUNT) {
      random[i] = random()
      var unique = true
      for (j in 0 until i) if (random[i] == random[j]) unique = false
      if (unique) i++
    }
    val order = (0 until Contest.COUNT).toMutableList()
    fun higher(a: Int, b: Int): Boolean { // DidContestantPlaceHigher(a, b): b beats a
      val x = order[a]
      val y = order[b]
      return when {
        totalPoints[x] != totalPoints[y] -> totalPoints[x] < totalPoints[y]
        round1Points[x] != round1Points[y] -> round1Points[x] < round1Points[y]
        else -> random[x] < random[y]
      }
    }
    for (a in 0 until Contest.COUNT - 1) {
      for (j in Contest.COUNT - 1 downTo a + 1) {
        if (higher(j - 1, j)) {
          val t = order[j - 1]
          order[j - 1] = order[j]
          order[j] = t
        }
      }
    }
    for (p in 0 until Contest.COUNT) finalStandings[order[p]] = p
    finished = true
  }

  /** GET_CONTEST_WINNER_ID. */
  val winner: Int
    get() = finalStandings.indexOf(0)
}
