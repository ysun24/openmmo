package de.fiereu.openmmo.server.game.contest

/**
 * The contest NPCs' move choice, a port of src/contest_ai.c: every set AI flag runs its script
 * (data/contest_ai_scripts.s, assembled by gen_contest_data.py) once per move slot, adjusting that
 * slot's score from 100; then a random slot with the highest score is used
 * (ContestAI_GetActionToUse). Only the commands the scripts use are implemented; an unknown opcode
 * ends the script. Two vanilla quirks are kept on purpose: `if_user_doesnt_have_move` compares move
 * ids against the effect constants the scripts pass (no BUGFIX), and `if_random_less_than` uses its
 * operand directly (the UBFIX reading, since vanilla reads past eContestAI.vars).
 */
class ContestAi(private val e: ContestEngine, private val me: Int, private val rng: ContestRng) {
  private val scores = IntArray(4) { 100 }
  private val program = e.data.aiProgram
  private var result = 0

  fun chooseMoveIndex(): Int {
    var flags = e.mons[me].aiFlags
    var flag = 0
    while (flags != 0L) {
      if (flags and 1L != 0L) runFlag(flag)
      flags = flags ushr 1
      flag++
    }
    while (true) {
      // MOD(Random(), MAX_MON_MOVES)
      val idx = (rng.next() and 0xFFFF) % 4
      if ((0 until 4).none { scores[idx] < scores[it] }) return idx
    }
  }

  private fun runFlag(flag: Int) {
    val start = e.data.aiTable.getOrNull(flag) ?: return
    for (slot in 0 until 4) {
      val move = e.mons[me].moves.getOrElse(slot) { 0 }
      if (move == 0) {
        scores[slot] = 0
        continue
      }
      run(start, slot, move)
    }
  }

  private fun turnContestant(turn: Int): Int =
      (0 until Contest.COUNT).firstOrNull { e.turnOrder[it] == turn } ?: Contest.COUNT

  private fun statusOf(c: Int) = e.status.getOrNull(c)

  private fun run(start: Int, slot: Int, move: Int) {
    val stack = ArrayDeque<Int>()
    var pc = start
    var steps = 0
    val st = e.status[me]
    val moves = e.mons[me].moves
    while (pc in program.indices && steps++ < 10_000) {
      val ins = program[pc]
      val arg0 = ins.args.getOrElse(0) { 0 }
      fun jumpIf(cond: Boolean) {
        pc = if (cond) ins.target else pc + 1
      }
      when (ins.op) {
        0x00 -> {
          scores[slot] = (scores[slot] + arg0).coerceIn(0, 255)
          pc++
        }
        0x04 -> jumpIf(e.appealNumber == (arg0 and 0xFF))
        0x05 -> jumpIf(e.appealNumber != (arg0 and 0xFF))
        0x07 -> jumpIf(e.applauseLevel < (arg0 and 0xFF))
        0x09 -> jumpIf(e.applauseLevel == (arg0 and 0xFF))
        0x0A -> jumpIf(e.applauseLevel != (arg0 and 0xFF))
        0x0C -> jumpIf(e.turnOrder[me] < (arg0 and 0xFF))
        0x0D -> jumpIf(e.turnOrder[me] > (arg0 and 0xFF))
        0x0E -> jumpIf(e.turnOrder[me] == (arg0 and 0xFF))
        0x0F -> jumpIf(e.turnOrder[me] != (arg0 and 0xFF))
        0x11 -> jumpIf(st.condition / 10 < (arg0 and 0xFF))
        0x13 -> jumpIf(st.condition / 10 == (arg0 and 0xFF))
        0x20 -> jumpIf(e.category == (arg0 and 0xFF))
        0x21 -> jumpIf(e.category != (arg0 and 0xFF))
        // move excitement compares signed
        0x23 -> jumpIf(e.moveExcitementOf(move) < arg0.toByte().toInt())
        0x24 -> jumpIf(e.moveExcitementOf(move) > arg0.toByte().toInt())
        0x25 -> jumpIf(e.moveExcitementOf(move) == arg0.toByte().toInt())
        0x26 -> jumpIf(e.moveExcitementOf(move) != arg0.toByte().toInt())
        0x28 -> jumpIf(e.data.move(move).effect == (arg0 and 0xFF))
        0x29 -> jumpIf(e.data.move(move).effect != (arg0 and 0xFF))
        0x2B -> jumpIf(e.data.effectOf(move).type == (arg0 and 0xFF))
        0x2C -> jumpIf(e.data.effectOf(move).type != (arg0 and 0xFF))
        0x2E -> {
          val appeal = e.data.effectOf(move).appeal
          jumpIf(moves.none { it != 0 && appeal < e.data.effectOf(it).appeal })
        }
        0x3C,
        0x3D,
        0x3E,
        0x3F -> {
          result = if (move != st.prevMove) 0 else st.moveRepeatCount + 1
          val a = arg0 and 0xFF
          jumpIf(
              when (ins.op) {
                0x3C -> result < a
                0x3D -> result > a
                0x3E -> result == a
                else -> result != a
              })
        }
        0x41,
        0x42 -> {
          val starter = moves.any { it != 0 && e.areMovesCombo(move, it) != 0 }
          jumpIf(if (ins.op == 0x41) starter else !starter)
        }
        0x44,
        0x45 -> {
          val finisher = moves.any { it != 0 && e.areMovesCombo(it, move) != 0 }
          jumpIf(if (ins.op == 0x44) finisher else !finisher)
        }
        0x47,
        0x48 -> {
          val would = st.prevMove != 0 && e.areMovesCombo(st.prevMove, move) != 0
          jumpIf(if (ins.op == 0x47) would else !would)
        }
        0x4A,
        0x4B,
        0x4C,
        0x4D -> {
          val c = statusOf(turnContestant(arg0))
          val v = (c?.condition ?: 0) / 10
          val n = ins.args.getOrElse(1) { 0 } and 0xFF
          jumpIf(
              when (ins.op) {
                0x4A -> v < n
                0x4B -> v > n
                0x4C -> v == n
                else -> v != n
              })
        }
        0x51,
        0x52 -> {
          val c = turnContestant(arg0)
          val used =
              if (c < Contest.COUNT && e.allowedToCombo(c))
                  if (e.data.move(e.status[c].prevMove).comboStarter != 0) 1 else 0
              else 0
          val n = ins.args.getOrElse(1) { 0 } and 0xFF
          jumpIf(if (ins.op == 0x51) used == n else used != n)
        }
        0x54,
        0x55 -> {
          val c = turnContestant(arg0)
          val can = c < Contest.COUNT && !e.isTurnDisabled(c)
          jumpIf(if (ins.op == 0x54) can else !can)
        }
        0x57,
        0x58 -> {
          val c = statusOf(turnContestant(arg0))
          val done = c?.completedComboFlag == true
          jumpIf(if (ins.op == 0x57) done else !done)
        }
        0x7D -> jumpIf((rng.next() and 0xFF) < (arg0 and 0xFF))
        0x7E -> jumpIf((rng.next() and 0xFF) > (arg0 and 0xFF))
        0x7F -> pc = ins.target
        0x80 -> {
          stack.addLast(pc + 1)
          pc = ins.target
        }
        0x81 -> {
          if (stack.isEmpty()) return
          pc = stack.removeLast()
        }
        0x83,
        0x84 -> {
          val has = moves.any { it != 0 && e.moveExcitementOf(it) == 1 }
          jumpIf(if (ins.op == 0x83) has else !has)
        }
        0x86,
        0x87 -> {
          val has = moves.any { it == arg0 }
          jumpIf(if (ins.op == 0x86) has else !has)
        }
        else -> return
      }
    }
  }

  /** The score per move slot after the scripts ran (for tests). */
  internal fun scoresAfterScripts(): List<Int> {
    var flags = e.mons[me].aiFlags
    var flag = 0
    while (flags != 0L) {
      if (flags and 1L != 0L) runFlag(flag)
      flags = flags ushr 1
      flag++
    }
    return scores.toList()
  }
}
