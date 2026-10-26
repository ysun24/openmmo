package de.fiereu.openmmo.client

import de.fiereu.openmmo.client.net.GameClient
import de.fiereu.openmmo.client.net.GameNames
import de.fiereu.openmmo.client.net.TradeClient
import de.fiereu.openmmo.net.game.packets.trade.TradePhase

/**
 * Script and stdin commands for trading:
 * ```
 * trade NAME           ask NAME to trade          trade-accept / trade-decline
 * offer-mon SLOT       toggle a party monster     offer-item ID QTY (QTY 0 removes)
 * offer-money N        offer money                trade-ready / trade-confirm / trade-cancel
 * trade-state          print the trade window     party / bag  print party+pc / bag+money
 * wait-trade PHASE [S] wait up to S seconds (default 15) for a trade phase, e.g. OPEN
 * sleep S              wait S seconds             /anything    a server chat command
 * ```
 *
 * Returns false for a command it does not know.
 */
fun runTradeCommand(game: GameClient, cmd: String): Boolean {
  val t = game.trade
  val parts = cmd.split(Regex("\\s+"))
  val arg = { i: Int -> parts.getOrNull(i)?.toIntOrNull() }
  when (parts[0]) {
    "trade" -> t.request(cmd.removePrefix("trade").trim())
    "trade-accept" -> t.accept()
    "trade-decline" -> t.decline()
    "trade-ready" -> t.ready()
    "trade-confirm" -> t.confirm()
    "trade-cancel" -> t.cancel()
    "offer-mon" -> t.toggleMon(arg(1) ?: return usage("offer-mon SLOT"))
    "offer-item" ->
        t.offerItem(arg(1) ?: return usage("offer-item ID QTY"), arg(2) ?: return usage("QTY"))
    "offer-money" -> t.offerMoney(arg(1) ?: return usage("offer-money N"))
    "trade-state" -> println("trade-state: ${TradeClient.describe(t.state)}")
    "party" -> printParty(t)
    "bag" -> {
      println("bag: money=${t.money ?: "?"}")
      t.bag.toSortedMap().forEach { (id, qty) -> println("  $qty x ${GameNames.item(id)} ($id)") }
    }
    "wait-trade" -> {
      val phase = TradePhase.valueOf(parts.getOrNull(1)?.uppercase() ?: return usage("wait-trade"))
      val end = System.currentTimeMillis() + (arg(2) ?: 15) * 1000L
      while (t.state.phase != phase && System.currentTimeMillis() < end) Thread.sleep(50)
      println("wait-trade $phase: ${if (t.state.phase == phase) "ok" else "timed out"}")
    }
    "sleep" -> Thread.sleep((parts.getOrNull(1)?.toDoubleOrNull() ?: 1.0).times(1000).toLong())
    else -> {
      if (!cmd.startsWith("/")) return false
      t.command(cmd)
    }
  }
  return true
}

private fun printParty(t: TradeClient) {
  println("party (${t.party.size}):")
  t.party.forEachIndexed { i, mon -> println("  ${TradeClient.monLine(i, mon)}") }
  if (t.pc.isNotEmpty()) {
    println("pc (${t.pc.size}):")
    t.pc.forEachIndexed { i, mon -> println("  ${TradeClient.monLine(i, mon)}") }
  }
}

private fun usage(text: String): Boolean {
  println("usage: $text")
  return true
}
