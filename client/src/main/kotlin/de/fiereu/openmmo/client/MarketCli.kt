package de.fiereu.openmmo.client

import de.fiereu.openmmo.client.net.GameClient
import de.fiereu.openmmo.client.net.MarketEntry
import de.fiereu.openmmo.client.net.MarketSortOrder
import de.fiereu.openmmo.client.net.SpeciesNames
import java.time.Instant

/**
 * Market and dev commands for the text client. Each one waits for the server's answer and prints
 * it, so a `--script` run shows the whole exchange:
 * ```
 * money                                  current balance
 * market-inv                             money, party (slot numbers for market-list-mon), bag
 * market-list-mon <slot> <price>         list the party monster in that slot (0-based)
 * market-list-item <id> <qty> <price>    list a bag stack
 * market-search [mon|item] [id] [min] [max] [price|price-desc|new]
 * market-buy <listingId>                 buy a listing
 * market-buy-first                       buy the first row of the last search
 * market-cancel <listingId>              take a listing back
 * market-mine                            your active listings
 * /givemon <dex> [lvl], /giveitem <id> [qty], /givemoney <n>   dev-only chat commands
 * wait <seconds>
 * ```
 *
 * Returns false for anything that is not one of these.
 */
fun runMarketCommand(game: GameClient, cmd: String): Boolean {
  val m = game.market
  val parts = cmd.trim().split(Regex("\\s+"))
  val args = parts.drop(1)
  fun int(i: Int): Int? = args.getOrNull(i)?.toIntOrNull()
  fun usage(text: String) = println("usage: $text")

  when (parts[0]) {
    "money" -> {
      refreshInventory(game)
      println("money: ${m.state.money}")
    }
    "market-inv" -> {
      refreshInventory(game)
      printInventory(game)
    }
    "market-list-mon" -> {
      val slot = int(0)
      val price = int(1)
      if (slot == null || price == null)
          return true.also { usage("market-list-mon <slot> <price>") }
      // Slots are resolved against the current party, which a /givemon or a sale may have changed.
      refreshInventory(game)
      m.drain()
      if (!m.listPokemon(slot, price)) return true.also { println("market: no party slot $slot") }
      printResult(game)
    }
    "market-list-item" -> {
      val id = int(0)
      val qty = int(1)
      val price = int(2)
      if (id == null || qty == null || price == null) {
        return true.also { usage("market-list-item <id> <qty> <price>") }
      }
      m.drain()
      m.listItem(id, qty, price)
      printResult(game)
    }
    "market-search" -> {
      val pokemon = args.getOrNull(0) != "item"
      val rest = if (args.firstOrNull() in setOf("mon", "item")) args.drop(1) else args
      val sort =
          when (rest.getOrNull(3)) {
            "price-desc" -> MarketSortOrder.PRICE_DESC
            "new" -> MarketSortOrder.NEWEST
            else -> MarketSortOrder.PRICE_ASC
          }
      m.drain()
      m.search(
          pokemon,
          rest.getOrNull(0)?.toIntOrNull()?.takeIf { it > 0 },
          rest.getOrNull(1)?.toIntOrNull(),
          rest.getOrNull(2)?.toIntOrNull(),
          sort)
      val page = m.awaitPage()
      if (page == null) println("market: no answer")
      else printRows(game, "search", m.state.browse, page.totalMatches)
    }
    "market-mine" -> {
      m.drain()
      m.requestMine()
      if (m.awaitPage() == null) println("market: no answer")
      else printRows(game, "mine", m.state.mine, m.state.mine.size)
    }
    "market-buy" -> {
      val id =
          args.getOrNull(0)?.toLongOrNull() ?: return true.also { usage("market-buy <listingId>") }
      m.drain()
      m.buy(id)
      printResult(game)
    }
    "market-buy-first" -> {
      val first =
          m.state.browse.firstOrNull() ?: return true.also { println("market: nothing to buy") }
      m.drain()
      m.buy(first.listingId)
      printResult(game)
    }
    "market-cancel" -> {
      val id =
          args.getOrNull(0)?.toLongOrNull()
              ?: return true.also { usage("market-cancel <listingId>") }
      m.drain()
      m.cancel(id)
      printResult(game)
    }
    "wait" -> Thread.sleep(((args.firstOrNull()?.toDoubleOrNull() ?: 1.0) * 1000).toLong())
    else -> {
      if (!cmd.startsWith("/")) return false
      val before = game.world.snapshot().chat.size
      m.chatCommand(cmd)
      Thread.sleep(800)
      game.world.snapshot().chat.drop(before).forEach { println("  server: ${it.message}") }
    }
  }
  return true
}

private fun refreshInventory(game: GameClient) {
  game.market.inventories.clear()
  game.market.requestInventory()
  if (game.market.awaitInventory() == null) println("market: no inventory answer")
}

private fun printInventory(game: GameClient) {
  val s = game.market.state
  println("money: ${s.money}")
  println("party (${s.party.size}):")
  s.party
      .sortedBy { it.containerSlot }
      .forEachIndexed { i, mon ->
        println(
            "  slot $i: ${SpeciesNames.name(mon.dexId)} Lv${mon.level} id=${mon.id} ot=${mon.ot}")
      }
  println("pc: ${s.pcCount} pokemon")
  println(
      "bag: " + s.items.joinToString { "item ${it.itemId} x${it.quantity}" }.ifEmpty { "empty" })
}

private fun printResult(game: GameClient) {
  val r = game.market.awaitResult()
  if (r == null) {
    println("market: no answer")
    return
  }
  println(
      "market: ${r.action} ${if (r.success) "ok" else "refused"} listing=${r.listingId} ${r.message}")
  // Inventory follows a successful action.
  if (r.success) game.market.awaitInventory(2000)?.let { println("money now: ${it.money}") }
}

private fun printRows(game: GameClient, label: String, rows: List<MarketEntry>, total: Int) {
  val self = game.market.selfId
  println("market $label: $total listing(s)")
  rows.forEach { e ->
    val mine = if (e.sellerId != null && e.sellerId == self) " (yours)" else ""
    println(
        "  #${e.listingId} ${e.describe()} price=${e.price} " +
            "expires=${Instant.ofEpochSecond(e.expiresAtEpoch)}$mine")
  }
}
