package de.fiereu.openmmo.client

import de.fiereu.openmmo.client.gfx.GameWindow
import de.fiereu.openmmo.client.gfx.WorldRenderer
import de.fiereu.openmmo.client.net.GameClient
import de.fiereu.openmmo.client.net.LoginClient
import de.fiereu.openmmo.client.net.NewCharacter
import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.common.io.PemKeyLoader
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * Text client.
 *
 * ```
 * client --user NAME --pass PW [--name CHAR] [--host 127.0.0.1] [--port 2106]
 *        [--key keys/build/game.public.pem] [--script "say hi; s; s; d"] [--stay SECONDS]
 *        [--gui true] [--snapshot out.png] [--decomp decomp]
 * ```
 *
 * `--gui true` opens the game window. `--snapshot FILE` writes one rendered frame after the script
 * has run, which is how the renderer is checked without a display.
 *
 * Without `--script` it reads commands from stdin: `w a s d` step, `W A S D` face, `say TEXT`,
 * `who`, `quit`.
 */
fun main(args: Array<String>) {
  val opts = parseArgs(args)
  val user = opts["user"] ?: usage("--user is required")
  val pass = opts["pass"] ?: usage("--pass is required")
  val host = opts["host"] ?: "127.0.0.1"
  val port = opts["port"]?.toInt() ?: 2106
  val keyPath =
      opts["key"] ?: System.getenv("OPENMMO_GAME_PUBLIC_KEY_FILE") ?: "keys/build/game.public.pem"
  val charName = opts["name"] ?: user
  val region = (opts["region"] ?: "0").toByte()

  val rootPublic = File(keyPath).inputStream().use(PemKeyLoader::loadEcPublic)
  val group = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
  try {
    val ticket = LoginClient(group, rootPublic).login(host, port, user, pass)
    println("login ok, game server at ${ticket.host}:${ticket.port}")
    val game = GameClient(group, rootPublic, NewCharacter(charName, startingRegion = region))
    game.connect(ticket)
    waitUntil(20_000) { game.world.snapshot().inWorld }
    printStatus(game)
    val renderer = WorldRenderer(File(opts["decomp"] ?: "decomp"))

    if (opts["gui"] == "true") {
      GameWindow(game, renderer).show()
      game.closeFuture()?.sync()
      return
    }

    val script = opts["script"]
    if (script != null) {
      script.split(';').map(String::trim).filter(String::isNotEmpty).forEach {
        run(game, it)
        Thread.sleep(400)
      }
      Thread.sleep((opts["stay"]?.toLong() ?: 2) * 1000)
      printStatus(game)
      opts["snapshot"]?.let { path ->
        val frame = renderer.render(game.world.snapshot())
        val scaled = java.awt.image.BufferedImage(frame.width * 3, frame.height * 3, frame.type)
        scaled.createGraphics().apply {
          drawImage(frame, 0, 0, scaled.width, scaled.height, null)
          dispose()
        }
        ImageIO.write(scaled, "png", File(path))
        println("snapshot written to $path")
      }
    } else {
      generateSequence { readlnOrNull() }
          .forEach { line ->
            if (line.trim() == "quit") return@forEach
            run(game, line.trim())
          }
    }
    game.disconnect()
  } finally {
    group.shutdownGracefully()
  }
}

private fun run(game: GameClient, cmd: String) {
  val dir =
      mapOf(
          'w' to Direction.UP, 'a' to Direction.LEFT, 's' to Direction.DOWN, 'd' to Direction.RIGHT)
  when {
    cmd.length == 1 && cmd[0] in dir -> game.step(dir.getValue(cmd[0]))
    cmd.length == 1 && cmd[0].lowercaseChar() in dir ->
        game.face(dir.getValue(cmd[0].lowercaseChar()))
    cmd.startsWith("say ") -> game.say(cmd.removePrefix("say "))
    cmd == "who" -> printStatus(game)
    cmd.isEmpty() -> {}
    else -> println("unknown command: $cmd")
  }
}

private fun printStatus(game: GameClient) {
  val s = game.world.snapshot()
  val self = s.self
  println(
      "status: char=${s.characterName} map=${s.map} size=${s.mapWidth}x${s.mapHeight} " +
          "pos=${self?.let { "(${it.x},${it.y}) ${it.facing}" }} inWorld=${s.inWorld}")
  s.entities.values
      .filter { it.id != s.selfId }
      .forEach { println("  other: ${it.name} at (${it.x},${it.y}) ${it.facing}") }
  s.chat.takeLast(5).forEach { println("  chat ${it.sender}: ${it.message}") }
}

private fun waitUntil(timeoutMs: Long, cond: () -> Boolean) {
  val end = System.currentTimeMillis() + timeoutMs
  while (!cond()) {
    if (System.currentTimeMillis() > end) {
      System.err.println("timed out waiting to enter the world")
      return
    }
    Thread.sleep(50)
  }
}

private fun parseArgs(args: Array<String>): Map<String, String> {
  val out = mutableMapOf<String, String>()
  var i = 0
  while (i < args.size) {
    val a = args[i]
    if (!a.startsWith("--") || i + 1 >= args.size) usage("bad argument: $a")
    out[a.removePrefix("--")] = args[i + 1]
    i += 2
  }
  return out
}

private fun usage(msg: String): Nothing {
  System.err.println(msg)
  System.err.println(
      "usage: client --user NAME --pass PW [--name CHAR] [--script \"say hi; s; d\"]")
  exitProcess(2)
}
