package de.fiereu.openmmo.web

import de.fiereu.openmmo.client.net.BattlePhase
import de.fiereu.openmmo.client.net.GameClient
import de.fiereu.openmmo.client.net.MarketSortOrder
import de.fiereu.openmmo.client.net.OverworldClient
import de.fiereu.openmmo.client.net.SocialClient
import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.net.game.packets.social.ChatChannel
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
          progress = client.progress.state,
          playSeconds = client.progress.playSeconds(),
          social = client.social.state,
          tradeExpiresAt = client.trade.expiresAtMs,
      )

  private fun battleOver(): Boolean {
    val b = client.battle.snapshot()
    return b.active && b.phase == BattlePhase.ENDED
  }

  private fun inBattle(): Boolean {
    val b = client.battle.snapshot()
    return b.active && b.phase != BattlePhase.ENDED
  }

  override fun step(dir: Direction, run: Boolean, seq: Int): String? = doStep(dir, run, seq, false)

  override fun stepJump(dir: Direction, run: Boolean, seq: Int): String? =
      doStep(dir, run, seq, true)

  private fun doStep(dir: Direction, run: Boolean, seq: Int, jump: Boolean): String? {
    // A finished battle's result screen goes away once the player walks.
    if (battleOver()) client.battle.close()
    try {
      if (!inBattle()) client.step(dir, run, jump)
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

  override fun field(action: String, arg: Int, arg2: Int): String? {
    if (inBattle()) return "not in a battle"
    val a =
        de.fiereu.openmmo.net.game.packets.field.FieldAction.entries.firstOrNull {
          it.name.lowercase().replace("_", "") == action
        } ?: return "unknown field action"
    return if (client.fieldAction(a, arg, arg2)) null else "close the dialog first"
  }

  override fun secretBase(action: String, decor: Int, x: Int, y: Int): String? {
    if (inBattle()) return "not in a battle"
    val a =
        de.fiereu.openmmo.net.game.packets.field.SecretBaseAction.entries.first {
          it.name.lowercase().replace("_", "") == action
        }
    client.send(de.fiereu.openmmo.net.game.packets.field.SecretBaseActionPacket(a, decor, x, y))
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

  override fun itemMove(item: Int, mon: Long, slot: Int): String? {
    if (inBattle()) return "not outside a battle"
    client.progress.useOnMove(item, mon, slot)
    return null
  }

  override fun learnReply(slot: Int?): String? {
    client.progress.learnReply(slot)
    return null
  }

  override fun itemRegister(item: Int): String? {
    client.progress.register(item)
    return null
  }

  override fun itemRegistered(): String? {
    if (inBattle()) return "not outside a battle"
    client.progress.useRegistered()
    return null
  }

  override fun daycareDeposit(mon: Long?): String? {
    if (mon == null) client.progress.daycareCancel() else client.progress.daycareDeposit(mon)
    return null
  }

  override fun daycareWithdraw(index: Int?): String? {
    if (index == null) client.progress.daycareCancel() else client.progress.daycareWithdraw(index)
    return null
  }

  override fun decorBuy(id: Int, qty: Int): String? {
    client.progress.decorBuy(id, qty)
    return null
  }

  override fun decorClose(): String? {
    client.progress.shopClosed()
    return null
  }

  override fun progressRefresh(): String? {
    client.progress.refresh()
    return null
  }

  private companion object {
    const val PARTY = OverworldClient.LIST_PARTY
    const val PC = OverworldClient.LIST_PC
    const val ANY = -1
  }

  override fun shopClose(): String? {
    client.overworld.closeShop()
    // r3-items: lets the clerk say "Please come again!".
    client.progress.shopClosed()
    return null
  }

  override fun battleFight(move: Int) = client.battle.fight(move)

  override fun battleFightAt(move: Int, pos: Int?, targetSide: String?, targetPos: Int?) =
      client.battle.fightAt(pos, move, targetSide, targetPos)

  override fun battleItem(item: Int, mon: Long?, moveSlot: Int?, pos: Int?): String? {
    if (!inBattle()) return "not in a battle"
    return client.battle.useItem(item, mon ?: 0, moveSlot ?: -1, pos)
  }

  override fun battleBallItem(item: Int) = client.battle.throwBall(item)

  override fun evolutionAnswer(proceed: Boolean) = client.battle.answerEvolution(proceed)

  override fun evolutionClose(): String? {
    client.battle.dismissEvolution()
    return null
  }

  override fun duelRulesDoubles(
      name: String,
      levelCap: Int,
      maxParty: Int,
      turnSeconds: Int,
      doubles: Boolean,
  ): String? {
    client.battle.challenge(
        name,
        de.fiereu.openmmo.net.game.packets.battle.DuelRules(
            levelCap, maxParty, turnSeconds, doubles))
    return null
  }

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

  // --- r3-social ---------------------------------------------------------------------------------

  override fun sayOn(channel: String, to: String?, text: String): String? {
    if (text.startsWith("/")) {
      client.command(text)
      return null
    }
    val ch =
        when (channel) {
          "local" -> ChatChannel.LOCAL
          "global" -> ChatChannel.GLOBAL
          "trade" -> ChatChannel.TRADE
          "guild" -> ChatChannel.GUILD
          "whisper" -> ChatChannel.WHISPER
          else -> return "bad channel: $channel"
        }
    client.chat(ch, text, to.orEmpty())
    return null
  }

  private inline fun social(block: SocialClient.() -> Unit): String? {
    client.social.block()
    return null
  }

  override fun socialRefresh() = social { refresh() }

  override fun friendAdd(name: String) = social { addFriend(name) }

  override fun friendRemove(name: String) = social { removeFriend(name) }

  override fun friendAnswer(name: String, accept: Boolean) = social { answerFriend(name, accept) }

  override fun block(name: String) = social { block(name) }

  override fun unblock(name: String) = social { unblock(name) }

  override fun profile(name: String) = social { profile(name) }

  override fun guildCreate(name: String, tag: String) = social { createGuild(name, tag) }

  override fun guildInvite(name: String) = social { inviteToGuild(name) }

  override fun guildAnswer(accept: Boolean) = social { answerInvite(accept) }

  override fun guildLeave() = social { leaveGuild() }

  override fun guildKick(name: String) = social { kick(name) }

  override fun guildRank(name: String, rank: Int) = social { setRank(name, rank) }

  override fun guildMotd(text: String) = social { setMotd(text) }

  override fun guildDisband() = social { disbandGuild() }

  override fun tradePcMon(slot: Int): String? {
    client.trade.togglePcMon(slot)
    return null
  }

  override fun tradeHistory() = social { requestTradeHistory() }

  override fun marketHistory(pokemon: Boolean, id: Int): String? {
    client.market.requestPriceHistory(pokemon, id)
    return null
  }

  override fun marketSales(): String? {
    client.market.requestSales()
    return null
  }

  override fun marketInfo(): String? {
    client.market.requestInfo()
    return null
  }

  override fun marketCancel(id: Long): String? {
    client.market.cancel(id)
    refreshMarket()
    return null
  }
}
