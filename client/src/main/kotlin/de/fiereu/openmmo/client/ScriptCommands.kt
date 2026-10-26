package de.fiereu.openmmo.client

import de.fiereu.openmmo.client.gfx.WorldRenderer
import de.fiereu.openmmo.client.net.GameClient
import de.fiereu.openmmo.common.enums.Direction
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Script commands for the overworld, so dialogs, warps and the mart can be driven headlessly:
 * ```
 * interact | z      the A button: talk to what is in front, or advance the open box
 * next              advance the open box only
 * B                 cancel: close the shop, answer NO, or advance
 * choose N          answer the open choice box with option N (1-based)
 * up | down         move the menu cursor
 * buy ITEM QTY      buy from the open mart (item name or id)
 * sell ITEM QTY     sell to the open mart
 * close             close the shop window
 * wait MS           sleep
 * idle [MS]         wait until no box, transition or cutscene holds the player (default 10 s)
 * go DIR N          take N steps (w/a/s/d), waiting for each to be accepted
 * snap FILE         write a 3x snapshot now
 * dialog | bag      print the open box, or money and bag
 * ui               print the overworld UI flags (dialog, shop, transition, script lock)
 * ```
 */
object ScriptCommands {
  @Volatile var renderer: WorldRenderer? = null

  private val dirs =
      mapOf(
          "w" to Direction.UP, "a" to Direction.LEFT, "s" to Direction.DOWN, "d" to Direction.RIGHT)

  fun run(game: GameClient, cmd: String): Boolean {
    val parts = cmd.split(Regex("\\s+"))
    val ow = game.overworld
    when (parts[0].lowercase()) {
      "interact",
      "z" -> ow.interact()
      "next" -> ow.advance()
      "b" -> ow.cancel()
      "up" -> ow.moveCursor(-1)
      "down" -> ow.moveCursor(1)
      "choose" -> ow.choose((parts.getOrNull(1)?.toIntOrNull() ?: 1) - 1)
      "buy",
      "sell" -> {
        val qty = parts.lastOrNull()?.toIntOrNull() ?: 1
        val name = parts.drop(1).dropLast(if (parts.size > 2) 1 else 0).joinToString(" ")
        val id = ow.itemIdOf(name)
        if (id == null) println("no item called '$name'")
        else {
          val ok = if (parts[0].lowercase() == "buy") ow.buy(id, qty) else ow.sell(id, qty)
          println("${parts[0]} $qty x ${ow.itemName(id)}: ${if (ok) "sent" else "no shop open"}")
        }
      }
      "close" -> ow.closeShop()
      "wait" -> Thread.sleep(parts.getOrNull(1)?.toLongOrNull() ?: 1000)
      "idle" -> idle(game, parts.getOrNull(1)?.toLongOrNull() ?: 10_000)
      "skip" -> skip(game, parts.getOrNull(1)?.toLongOrNull() ?: 30_000)
      "go" -> {
        val d = dirs[parts.getOrNull(1)] ?: return false
        repeat(parts.getOrNull(2)?.toIntOrNull() ?: 1) {
          idle(game, 5_000)
          game.step(d)
          Thread.sleep(280)
        }
      }
      "snap" -> parts.getOrNull(1)?.let { snapshot(game, File(it)) }
      "dialog" -> {
        val d = game.world.snapshot().overworld?.dialog
        println(
            if (d == null) "dialog: none"
            else
                "dialog: page ${d.page + 1}/${d.pages.size} '${d.pages[d.page].joinToString(" ")}'" +
                    if (d.choices.isEmpty()) "" else " choices=${d.choices} cursor=${d.cursor}")
      }
      "ui" -> {
        val v = game.world.snapshot().overworld
        println(
            "ui: dialog=${v?.dialog != null} shop=${v?.shop != null} transitioning=${v?.transitioning} " +
                "scriptLocked=${v?.scriptLocked} inScript=${v?.inScript}")
      }
      "bag" -> {
        val v = game.world.snapshot().overworld
        println(
            "money: ${v?.money} bag: " +
                v?.bag?.entries?.joinToString { "${ow.itemName(it.key)} x${it.value}" })
      }
      else -> return false
    }
    return true
  }

  /**
   * Presses A through every box (choice boxes take their first option) until the player has been
   * free for two seconds, the way a player mashing A through a cutscene would.
   */
  private fun skip(game: GameClient, timeoutMs: Long) {
    val end = System.currentTimeMillis() + timeoutMs
    var freeSince = System.currentTimeMillis()
    while (System.currentTimeMillis() < end) {
      val v = game.world.snapshot().overworld ?: return
      val d = v.dialog
      if (d != null) {
        val box = d.pages.getOrNull(d.page)?.joinToString(" ")
        println(
            "skip: '$box'" +
                if (d.onLastPage && d.choices.isNotEmpty()) " -> ${d.choices[0]}" else "")
        game.overworld.advance()
        freeSince = System.currentTimeMillis()
        Thread.sleep(250)
        continue
      }
      if (v.transitioning || v.scriptLocked || v.inScript || v.shop != null)
          freeSince = System.currentTimeMillis()
      if (System.currentTimeMillis() - freeSince > 2_000) return
      Thread.sleep(50)
    }
  }

  private fun idle(game: GameClient, timeoutMs: Long) {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
      val v = game.world.snapshot().overworld
      if (v == null || (v.dialog == null && !v.transitioning && !v.scriptLocked && !v.inScript))
          return
      Thread.sleep(50)
    }
  }

  fun snapshot(game: GameClient, file: File) {
    val r = renderer ?: return
    val frame = r.render(game.world.snapshot())
    val scaled = BufferedImage(frame.width * 3, frame.height * 3, frame.type)
    scaled.createGraphics().apply {
      drawImage(frame, 0, 0, scaled.width, scaled.height, null)
      dispose()
    }
    file.absoluteFile.parentFile?.mkdirs()
    ImageIO.write(scaled, "png", file)
    println("snapshot written to $file")
  }
}
