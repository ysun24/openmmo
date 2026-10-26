package de.fiereu.openmmo.client

import de.fiereu.openmmo.client.net.BattlePhase
import de.fiereu.openmmo.client.net.BattleState
import de.fiereu.openmmo.client.net.GameClient

/**
 * Script and stdin commands for battles, so they can be driven headless:
 * ```
 * /any slash command      sent to the server as a chat command, e.g. /givemon 4 10
 * battle-test [dex] [lv]  /testbattle, then wait for the first prompt
 * fight N | switch N      pick move N / party slot N (1-based), waiting for the prompt
 * run | ball              flee / throw a Poke Ball
 * auto [N]                fight with the first usable move until the battle ends (N turns max)
 * learn N | skip          answer a move-learn prompt
 * duel NAME               challenge a player; accept | decline answer a challenge
 * wait-invite | wait-battle | wait-end [secs]
 * battle                  print the battle state
 * sleep SECS
 * ```
 *
 * Returns false when [cmd] is not a battle command.
 */
fun runBattleCommand(game: GameClient, cmd: String): Boolean {
  val b = game.battle
  val parts = cmd.trim().split(Regex("\\s+"))
  val arg = parts.getOrNull(1)
  fun report(error: String?) = error?.let { println("battle: $it") }
  when (parts[0]) {
    "battle-test" -> {
      val before = b.snapshot().battlesFinished
      game.command("/testbattle ${arg ?: 19} ${parts.getOrNull(2) ?: 3}")
      waitFor(15) { it.active && it.battlesFinished == before && it.phase == BattlePhase.ACTION }
      printBattle(b.snapshot())
    }
    "fight" -> {
      waitFor(30) { it.phase == BattlePhase.ACTION }
      report(b.fight((arg?.toIntOrNull() ?: 1) - 1))
      settle()
    }
    "switch" -> {
      waitFor(30) { it.phase == BattlePhase.ACTION || it.phase == BattlePhase.FORCED_SWITCH }
      report(b.switchTo((arg?.toIntOrNull() ?: 2) - 1))
      settle()
    }
    "run" -> {
      waitFor(30) { it.phase == BattlePhase.ACTION }
      report(b.run())
      settle()
    }
    "ball",
    "catch" -> {
      waitFor(30) { it.phase == BattlePhase.ACTION }
      report(b.throwBall())
      settle()
    }
    "auto" -> autoFight(game, arg?.toIntOrNull() ?: 100)
    "learn" -> report(b.learn((arg?.toIntOrNull() ?: 1) - 1))
    "skip" -> report(b.learn(null))
    "duel" -> b.challenge(arg ?: return true.also { println("battle: duel needs a name") })
    "accept" -> {
      waitFor(30) { it.pendingInviteFrom != null }
      report(b.answerInvite(true))
    }
    "decline" -> report(b.answerInvite(false))
    "wait-invite" -> waitFor(arg?.toLongOrNull() ?: 30) { it.pendingInviteFrom != null }
    "wait-battle" ->
        waitFor(arg?.toLongOrNull() ?: 30) { it.active && it.phase != BattlePhase.ENDED }
    "wait-end" -> waitFor(arg?.toLongOrNull() ?: 60) { it.phase == BattlePhase.ENDED }
    "battle" -> printBattle(b.snapshot())
    "sleep" -> Thread.sleep(((arg?.toDoubleOrNull() ?: 1.0) * 1000).toLong())
    else -> {
      if (cmd.startsWith("/")) {
        game.command(cmd)
        return true
      }
      return false
    }
  }
  return true
}

/** Fights with the first move that has pp left until the battle is over. */
private fun autoFight(game: GameClient, maxTurns: Int) {
  val b = game.battle
  var turns = 0
  while (turns < maxTurns) {
    waitFor(60) {
      it.phase == BattlePhase.ACTION ||
          it.phase == BattlePhase.FORCED_SWITCH ||
          it.phase == BattlePhase.ENDED ||
          it.pendingLearn != null
    }
    val s = b.snapshot()
    when {
      s.pendingLearn != null -> b.learn(null)
      s.phase == BattlePhase.ENDED -> break
      s.phase == BattlePhase.FORCED_SWITCH -> {
        val next = s.party.indexOfFirst { !it.fainted }
        if (next < 0) break
        b.switchTo(next)
      }
      s.phase == BattlePhase.ACTION -> {
        val moves = s.me?.moves.orEmpty()
        val pick = moves.indexOfFirst { it.pp > 0 }.coerceAtLeast(0)
        b.fight(pick)
        turns++
      }
      else -> break // timed out
    }
    Thread.sleep(150)
  }
  settle()
  printBattle(b.snapshot())
}

private var waiting: GameClient? = null

/** Set by Main so the wait helpers know which client to watch. */
fun watchBattles(game: GameClient) {
  waiting = game
}

private fun waitFor(seconds: Long, cond: (BattleState) -> Boolean) {
  val game = waiting ?: return
  val end = System.currentTimeMillis() + seconds * 1000
  while (!cond(game.battle.snapshot())) {
    if (System.currentTimeMillis() > end) {
      println("battle: timed out after ${seconds}s (phase=${game.battle.snapshot().phase})")
      return
    }
    Thread.sleep(50)
  }
}

/** Lets the turn's packets land before the next command reads the state. */
private fun settle() = Thread.sleep(600)

fun printBattle(s: BattleState) {
  if (!s.active) {
    println("battle: none (finished ${s.battlesFinished})")
    return
  }
  fun mon(m: de.fiereu.openmmo.client.net.BattleMon?) =
      m?.let { "${it.name} Lv${it.level} HP ${it.hp}/${it.maxHp}" } ?: "-"
  println(
      "battle: ${if (s.wild) "wild" else "vs ${s.opponentName.ifEmpty { "trainer" }}"} " +
          "phase=${s.phase} result=${s.result}")
  println("  foe: ${mon(s.foe)}")
  println("  me:  ${mon(s.me)}")
  println("  moves: " + s.me?.moves?.joinToString { "${it.name} ${it.pp}/${it.maxPp}" })
  println("  party: " + s.party.joinToString { "${it.name} ${it.hp}/${it.maxHp}" })
  s.log.takeLast(8).forEach { println("  | $it") }
}
