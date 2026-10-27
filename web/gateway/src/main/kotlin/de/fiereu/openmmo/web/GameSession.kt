package de.fiereu.openmmo.web

import de.fiereu.openmmo.client.net.BattlePhase
import de.fiereu.openmmo.client.net.GameClient
import de.fiereu.openmmo.client.net.MarketSortOrder
import de.fiereu.openmmo.client.net.OverworldClient
import de.fiereu.openmmo.common.enums.Direction
import java.util.concurrent.atomic.AtomicInteger

/** [GameActions] over one real [GameClient]. Called off the netty event loop, one at a time. */
class GameSession(val client: GameClient) : GameActions {
  /** The last `step.seq` applied, echoed as `world.ackSeq`. */
  val ackSeq = AtomicInteger(0)

  fun sources(moving: Set<Long>) =
      Sources(
          world = client.world.snapshot(),
          battle = client.battle.snapshot(),
          trade = client.trade.state,
          party = client.trade.party,
          pc = client.trade.pc,
          tradeBag = client.trade.bag,
          tradeMoney = client.trade.money,
          market = client.market.state,
          ackSeq = ackSeq.get(),
          moving = moving,
      )

  private fun battleOver(): Boolean {
    val b = client.battle.snapshot()
    return b.active && b.phase == BattlePhase.ENDED
  }

  private fun inBattle(): Boolean {
    val b = client.battle.snapshot()
    return b.active && b.phase != BattlePhase.ENDED
  }

  override fun step(dir: Direction, run: Boolean, seq: Int): String? {
    // A finished battle's result screen goes away once the player walks.
    if (battleOver()) client.battle.close()
    try {
      if (!inBattle()) client.step(dir, run)
    } finally {
      // Acknowledged even when refused: the browser then sees the unchanged tile and snaps back.
      ackSeq.set(seq)
    }
    return null
  }

  override fun face(dir: Direction): String? {
    if (inBattle()) return null
    client.face(dir)
    return null
  }

  override fun say(text: String): String? {
    client.say(text)
    return null
  }

  override fun a(): String? {
    if (battleOver()) {
      client.battle.close()
      return null
    }
    if (inBattle()) return null
    client.overworld.interact()
    return null
  }

  override fun b(): String? {
    if (battleOver()) {
      client.battle.close()
      return null
    }
    client.overworld.cancel()
    return null
  }

  override fun cursor(delta: Int): String? {
    client.overworld.moveCursor(delta)
    return null
  }

  override fun choose(index: Int): String? =
      if (client.overworld.choose(index)) null else "no choice is open"

  override fun shopBuy(item: Int, qty: Int): String? =
      if (client.overworld.buy(item, qty)) null else "no shop is open"

  override fun shopSell(item: Int, qty: Int): String? =
      if (client.overworld.sell(item, qty)) null else "no shop is open"

  override fun itemUse(item: Int, mon: Long?): String? {
    if (inBattle()) return "not outside a battle"
    return if (client.overworld.useItem(item, mon ?: 0)) null else "close the dialog first"
  }

  override fun partyMove(from: Int, to: Int): String? {
    client.overworld.moveMonster(PARTY, from, PARTY, to)
    return null
  }

  override fun pcDeposit(slot: Int): String? {
    client.overworld.moveMonster(PARTY, slot, PC, ANY)
    return null
  }

  override fun pcWithdraw(slot: Int): String? {
    client.overworld.moveMonster(PC, slot, PARTY, ANY)
    return null
  }

  private companion object {
    const val PARTY = OverworldClient.LIST_PARTY
    const val PC = OverworldClient.LIST_PC
    const val ANY = -1
  }

  override fun shopClose(): String? {
    client.overworld.closeShop()
    return null
  }

  override fun battleFight(move: Int) = client.battle.fight(move)

  override fun battleSwitch(slot: Int) = client.battle.switchTo(slot)

  override fun battleRun() = client.battle.run()

  override fun battleBall() = client.battle.throwBall()

  override fun battleLearn(slot: Int?) = client.battle.learn(slot)

  override fun battleClose(): String? {
    if (!battleOver()) return "the battle is not over"
    client.battle.close()
    return null
  }

  override fun duel(name: String): String? {
    client.battle.challenge(name)
    return null
  }

  override fun duelRules(name: String, levelCap: Int, maxParty: Int, turnSeconds: Int): String? {
    client.battle.challenge(
        name, de.fiereu.openmmo.net.game.packets.battle.DuelRules(levelCap, maxParty, turnSeconds))
    return null
  }

  override fun duelAnswer(accept: Boolean) = client.battle.answerInvite(accept)

  override fun tradeRequest(name: String): String? {
    client.trade.request(name)
    return null
  }

  override fun tradeAction(action: String): String? {
    val t = client.trade
    when (action) {
      "accept" -> t.accept()
      "decline" -> t.decline()
      "ready" -> t.ready()
      "confirm" -> t.confirm()
      "cancel" -> t.cancel()
      else -> return "unknown trade action"
    }
    return null
  }

  override fun tradeMon(slot: Int): String? {
    client.trade.toggleMon(slot)
    return null
  }

  override fun tradeItem(item: Int, qty: Int): String? {
    client.trade.offerItem(item, qty)
    return null
  }

  override fun tradeMoney(amount: Int): String? {
    client.trade.offerMoney(amount)
    return null
  }

  override fun marketSearch(
      pokemon: Boolean,
      id: Int?,
      min: Int?,
      max: Int?,
      sort: MarketSortOrder,
      page: Int,
  ): String? {
    client.market.drain()
    client.market.search(pokemon, id, min, max, sort, page)
    return null
  }

  override fun marketMine(): String? {
    client.market.drain()
    client.market.requestMine()
    return null
  }

  override fun marketInventory(): String? {
    client.market.drain()
    client.market.requestInventory()
    return null
  }

  /** After anything that moves goods, ask for the inventory and own listings again. */
  private fun refreshMarket() {
    // The client also queues every reply for scripts; nothing here reads them, so keep them empty.
    client.market.drain()
    client.market.requestInventory()
    client.market.requestMine()
  }

  override fun marketListMon(slot: Int, price: Int): String? {
    if (client.market.state.party.isEmpty()) {
      // The market's own party list is filled by an inventory reply; fetch it first.
      client.market.drain()
      client.market.requestInventory()
      client.market.awaitInventory(3000)
    }
    if (!client.market.listPokemon(slot, price)) return "no monster in slot $slot"
    refreshMarket()
    return null
  }

  override fun marketListItem(item: Int, qty: Int, price: Int): String? {
    client.market.listItem(item, qty, price)
    refreshMarket()
    return null
  }

  override fun marketBuy(id: Long): String? {
    client.market.buy(id)
    refreshMarket()
    return null
  }

  override fun marketCancel(id: Long): String? {
    client.market.cancel(id)
    refreshMarket()
    return null
  }
}
