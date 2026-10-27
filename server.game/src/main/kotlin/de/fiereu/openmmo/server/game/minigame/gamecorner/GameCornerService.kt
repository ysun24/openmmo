package de.fiereu.openmmo.server.game.minigame.gamecorner

import de.fiereu.network.SessionContext
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.minigame.MAX_COINS
import de.fiereu.openmmo.server.game.minigame.MinigameChannel
import de.fiereu.openmmo.server.game.minigame.MinigameSave
import de.fiereu.openmmo.server.game.minigame.MinigameTopic
import de.fiereu.openmmo.server.game.minigame.action
import de.fiereu.openmmo.server.game.minigame.int
import de.fiereu.openmmo.server.game.minigame.jsonOf
import de.fiereu.openmmo.server.game.minigame.sendCoins
import de.fiereu.openmmo.server.game.services.StoryService
import de.fiereu.openmmo.server.game.session.PlayerState
import de.fiereu.openmmo.story.generated.kanto.KantoFlags
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/**
 * The Game Corner: both slot machines, Mauville's roulette, and the coin case. Topics "slots",
 * "roulette", "coins". A game is opened by its script special (PlaySlotMachine / PlayRoulette)
 * and the script waits until the player leaves it, like the decomp's waitstate.
 */
@Singleton
class GameCornerService
@Inject
constructor(
    private val save: MinigameSave,
    private val story: StoryService,
    private val items: ItemRegistry,
    private val characters: de.fiereu.openmmo.server.game.storage.CharacterStore,
) : MinigameTopic {
  override val topics = setOf("slots", "roulette", "coins")

  private val rng: () -> Int = { ThreadLocalRandom.current().nextInt(0x10000) }

  private class SlotGame(
      val kanto: Boolean,
      val frlg: FrlgSlotMachine?,
      val emerald: EmeraldSlotMachine?,
      val done: CompletableDeferred<Unit> = CompletableDeferred(),
      var lastMs: Long = System.currentTimeMillis(),
      val subPixels: IntArray = IntArray(3),
      var lastPayout: Int = 0,
      var message: String = "",
  )

  private class RouletteGame(
      val roulette: Roulette,
      val done: CompletableDeferred<Unit> = CompletableDeferred(),
      val startMs: Long = System.currentTimeMillis(),
      var last: Roulette.Throw? = null,
      var message: String = "",
  )

  private val slots = ConcurrentHashMap<Long, SlotGame>()
  private val roulettes = ConcurrentHashMap<Long, RouletteGame>()

  /** The coin case: FireRed's FLAG_GOT_COIN_CASE, or the COIN CASE in the bag (both games). */
  fun hasCoinCase(charId: Long): Boolean {
    val id = items.idOrNull(Items.COIN_CASE)
    val inBag = id != null && (characters.getCharacter(charId)?.items?.get(id) ?: 0) > 0
    return inBag || story.isFlagSet(charId, KantoFlags.FLAG_GOT_COIN_CASE)
  }

  /** Dev: FireRed's coin case flag. */
  fun giveCoinCaseFlag(charId: Long) = story.setFlag(charId, KantoFlags.FLAG_GOT_COIN_CASE)

  suspend fun pushCoins(session: SessionContext, charId: Long) =
      sendCoins(session, save.coins(charId), hasCoinCase(charId))

  // --- slots ------------------------------------------------------------------------------------

  /** PlaySlotMachine: plays machine [machineId] until the player quits. */
  suspend fun playSlots(session: SessionContext, state: PlayerState, machineId: Int) {
    val charId = state.characterId ?: return
    val kanto = state.regionId == 0
    val game =
        if (kanto) SlotGame(true, FrlgSlotMachine(machineId, rng), null)
        else SlotGame(false, null, EmeraldSlotMachine(machineId, rng))
    slots.put(charId, game)?.done?.complete(Unit)
    pushSlots(session, charId, game)
    pushCoins(session, charId)
    try {
      withTimeoutOrNull(GAME_TIMEOUT) { game.done.await() }
    } finally {
      slots.remove(charId, game)
      MinigameChannel.close(session, "slots")
    }
  }

  private suspend fun pushSlots(session: SessionContext, charId: Long, g: SlotGame) {
    val coins = save.coins(charId)
    val body =
        if (g.kanto) {
          val m = g.frlg!!
          jsonOf(
              "game" to "frlg",
              "machine" to m.machineIdx,
              "coins" to coins,
              "bet" to m.bet,
              "reels" to m.reelPositions.toList(),
              "spinning" to m.spinning,
              "current" to m.currentReel,
              "speed" to EmeraldSlotMachine.REEL_NORMAL_SPEED,
              "payout" to g.lastPayout,
              "lines" to m.winFlags.indices.filter { m.winFlags[it] },
              "msg" to g.message,
              "strips" to FrlgSlotMachine.REEL_ICONS.map { it.toList() },
          )
        } else {
          val m = g.emerald!!
          jsonOf(
              "game" to "emerald",
              "machine" to m.machineId,
              "coins" to coins,
              "bet" to m.bet,
              "reels" to m.reelPositions.toList(),
              "spinning" to m.spinning,
              "current" to m.currentReel,
              "speed" to m.reelSpeed,
              "payout" to g.lastPayout,
              "lines" to m.matchLines.toList(),
              "bolts" to m.pikaPowerBolts,
              "reelTime" to m.reelTimeSpinsLeft,
              "reelTimeDraw" to m.lastReelTimeDraw,
              "lucky" to m.luckyGame,
              "replay" to m.replay,
              "msg" to g.message,
              "strips" to EmeraldSlotMachine.REEL_SYMBOLS.map { it.toList() },
          )
        }
    MinigameChannel.send(session, "slots", body)
  }

  private suspend fun onSlots(session: SessionContext, charId: Long, body: JsonObject) {
    val g = slots[charId] ?: return
    val now = System.currentTimeMillis()
    g.message = ""
    when (body.action()) {
      "bet" -> {
        val want = (body.int("n") ?: 1).coerceIn(1, 3)
        val spinning = g.frlg?.spinning ?: g.emerald!!.spinning
        if (spinning) return
        val bet = g.frlg?.bet ?: g.emerald!!.bet
        val coins = save.coins(charId)
        // Emerald's R needs the coins for the whole 3 (SlotTask_HandleBetInput); FireRed bets
        // what is left.
        var n = (want - bet).coerceAtLeast(0).coerceAtMost(coins)
        if (!g.kanto && want == 3 && coins < 3 - bet) {
          g.message = "You don't have three COINS."
          n = 0
        }
        if (coins == 0) g.message = if (g.kanto) "You've run out of COINS." else "You've run out of COINS. Game over!"
        if (n > 0 && save.addCoins(charId, -n)) {
          g.frlg?.addBet(n)
          g.emerald?.addBet(n)
        }
        val newBet = g.frlg?.bet ?: g.emerald!!.bet
        // Betting the maximum starts the reels by itself (Emerald, and FireRed's state 1).
        if (newBet >= 3) startSpin(g)
      }
      "spin" -> startSpin(g)
      "stop" -> {
        val frames = ((now - g.lastMs) * 60 / 1000).toInt().coerceAtLeast(0)
        g.lastMs = now
        g.frlg?.let { m ->
          if (!m.spinning) return
          m.advance(frames / FrlgSlotMachine.FRAMES_PER_ICON)
          m.stopCurrentReel()
          if (!m.spinning) settle(charId, g, m.payout, m.rewardClass)
        }
        g.emerald?.let { m ->
          if (!m.spinning) return
          m.advanceFrames(frames, g.subPixels)
          m.stopCurrentReel()
          if (!m.spinning) settle(charId, g, m.payout, if (m.matches != 0) 1 else 0)
        }
      }
      "quit" -> {
        val spinning = g.frlg?.spinning ?: g.emerald!!.spinning
        if (spinning) return
        // A bet not yet spun goes back, like the decomp's quit prompt refunding nothing: the GBA
        // asks first; here the coins return so a closed window never eats a bet.
        val bet = g.frlg?.bet ?: g.emerald!!.bet
        if (bet > 0) save.addCoins(charId, bet)
        g.done.complete(Unit)
        pushCoins(session, charId)
        return
      }
      else -> return
    }
    pushSlots(session, charId, g)
    pushCoins(session, charId)
  }

  private fun startSpin(g: SlotGame) {
    val ok = g.frlg?.spin() ?: g.emerald!!.spin()
    if (ok) {
      g.lastMs = System.currentTimeMillis()
      g.subPixels.fill(0)
      g.lastPayout = 0
      g.emerald?.let { m ->
        if (m.lastReelTimeDraw >= 0)
            g.message =
                if (m.lastReelTimeDraw > 0) "REEL TIME! ${m.lastReelTimeDraw} free spins at a slower speed!"
                else "REEL TIME... The machine broke down. Too bad!"
      }
    }
  }

  private suspend fun settle(charId: Long, g: SlotGame, payout: Int, rewardClass: Int) {
    g.lastPayout = payout
    if (payout > 0) {
      val coins = save.coins(charId)
      save.setCoins(charId, (coins + payout).coerceAtMost(MAX_COINS))
      g.message = "You won $payout COINS!"
    } else if (rewardClass == 0) {
      g.message = if (g.kanto) "Darn… No payout." else "Too bad…"
    }
    g.emerald?.let { m -> if (m.replay) g.message = "REPLAY! Spinning again for free." }
    // A REPLAY in Emerald keeps the bet and spins straight on (SlotTask_EndPayout).
    g.emerald?.let { m -> if (m.replay) startSpin(g) }
  }

  // --- roulette ---------------------------------------------------------------------------------

  /** PlayRoulette: VAR_0x8004 & 1 is the table, & ROULETTE_SPECIAL_RATE the special rate. */
  suspend fun playRoulette(session: SessionContext, state: PlayerState, var8004: Int) {
    val charId = state.characterId ?: return
    val game =
        RouletteGame(Roulette(var8004 and 1, var8004 and ROULETTE_SPECIAL_RATE != 0, rng))
    roulettes.put(charId, game)?.done?.complete(Unit)
    pushRoulette(session, charId, game)
    pushCoins(session, charId)
    try {
      withTimeoutOrNull(GAME_TIMEOUT) { game.done.await() }
    } finally {
      roulettes.remove(charId, game)
      MinigameChannel.close(session, "roulette")
    }
  }

  private fun wheelAngle(g: RouletteGame, now: Long): Int =
      // One degree a frame (sRouletteTables wheelSpeed 1), counter-clockwise.
      Math.floorMod(((now - g.startMs) * 60 / 1000).toInt(), 360)

  private suspend fun pushRoulette(session: SessionContext, charId: Long, g: RouletteGame) {
    val r = g.roulette
    MinigameChannel.send(
        session,
        "roulette",
        jsonOf(
            "table" to r.table,
            "special" to r.specialRate,
            "minBet" to r.minBet,
            "coins" to save.coins(charId),
            "ball" to r.ballNum,
            "balls" to Roulette.BALLS_PER_ROUND,
            "hits" to r.hitSquares.take(r.ballNum),
            "multipliers" to (0..Roulette.NUM_GRID_SELECTIONS).map { r.multiplier(it) },
            "last" to
                g.last?.let {
                  jsonOf(
                      "slot" to it.slot,
                      "square" to it.square,
                      "won" to it.won,
                      "multiplier" to it.multiplier,
                      "payout" to it.payout)
                },
            "slots" to Roulette.SLOT_SQUARES.toList(),
            "startMs" to 0,
            "msg" to g.message,
        ))
  }

  private suspend fun onRoulette(session: SessionContext, charId: Long, body: JsonObject) {
    val g = roulettes[charId] ?: return
    val r = g.roulette
    g.message = ""
    when (body.action()) {
      "bet" -> {
        val sel = body.int("selection") ?: return
        if (sel !in 1..Roulette.NUM_GRID_SELECTIONS) return
        if (r.roundOver) r.newRound()
        if (r.multiplier(sel) == 0) {
          g.message = "That spot is already taken."
        } else if (!save.addCoins(charId, -r.minBet)) {
          g.message = "You don't have enough COINS."
        } else {
          val t = r.throwBall(sel, wheelAngle(g, System.currentTimeMillis()))
          g.last = t
          if (t.won) {
            val coins = save.coins(charId)
            save.setCoins(charId, (coins + t.payout).coerceAtMost(MAX_COINS))
            g.message =
                if (t.multiplier == Roulette.MAX_MULTIPLIER) "Jackpot! You've won ${t.payout} COINS!"
                else "It's a hit! You've won ${t.payout} COINS!"
          } else g.message = "Nothing doing!"
          if (r.roundOver) g.message += " The round is over."
        }
      }
      "newround" -> r.newRound().also { g.last = null }
      "quit" -> {
        g.done.complete(Unit)
        pushCoins(session, charId)
        return
      }
      else -> return
    }
    pushRoulette(session, charId, g)
    pushCoins(session, charId)
  }

  override suspend fun onAction(
      session: SessionContext,
      state: PlayerState,
      topic: String,
      body: JsonObject
  ) {
    val charId = state.characterId ?: return
    when (topic) {
      "slots" -> onSlots(session, charId, body)
      "roulette" -> onRoulette(session, charId, body)
      "coins" -> pushCoins(session, charId)
    }
  }

  override fun onDisconnect(charId: Long) {
    slots.remove(charId)?.done?.complete(Unit)
    roulettes.remove(charId)?.done?.complete(Unit)
    save.evict(charId)
  }

  companion object {
    /** ROULETTE_SPECIAL_RATE (include/constants/roulette.h). */
    const val ROULETTE_SPECIAL_RATE = 1 shl 7
    val GAME_TIMEOUT = 30.minutes
  }
}
