package de.fiereu.openmmo.web

import de.fiereu.openmmo.client.net.SpeciesNames
import de.fiereu.openmmo.common.io.PemKeyLoader
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import kotlin.system.exitProcess

private val log = KotlinLogging.logger {}

/**
 * The web gateway: static files plus `/ws`, one headless game client per socket.
 *
 * ```
 * web.gateway [--host 127.0.0.1] [--port 8080] [--static web/static]
 *             [--login-host 127.0.0.1] [--login-port 2106]
 *             [--key keys/build/game.public.pem] [--decomp decomp] [--allow-origin URL[,URL]]
 *             [--game-host HOST] [--trust-forwarded true|false]
 * ```
 */
fun main(args: Array<String>) {
  val opts = parseArgs(args)
  val config =
      GatewayConfig(
          host = opts["host"] ?: "127.0.0.1",
          port = opts["port"]?.toIntOrNull() ?: 8080,
          staticRoot = File(opts["static"] ?: "web/static"),
          loginHost = opts["login-host"] ?: "127.0.0.1",
          loginPort = opts["login-port"]?.toIntOrNull() ?: 2106,
          decomp = File(opts["decomp"] ?: "decomp"),
          allowedOrigins =
              opts["allow-origin"]
                  ?.split(',')
                  ?.map(String::trim)
                  ?.filter(String::isNotEmpty)
                  ?.toSet()
                  .orEmpty(),
          gameHost = opts["game-host"]?.trim()?.takeIf { it.isNotEmpty() },
          trustForwarded = opts["trust-forwarded"]?.toBooleanStrictOrNull() ?: false,
      )
  val keyPath =
      opts["key"] ?: System.getenv("OPENMMO_GAME_PUBLIC_KEY_FILE") ?: "keys/build/game.public.pem"
  val rootPublic = File(keyPath).inputStream().use(PemKeyLoader::loadEcPublic)
  if (!config.staticRoot.isDirectory) log.warn { "static root ${config.staticRoot} does not exist" }
  SpeciesNames.load(config.decomp)

  val gateway = Gateway(config, rootPublic)
  val address = gateway.start()
  log.info {
    "Web gateway on http://${address.hostString}:${address.port}/ (static ${config.staticRoot}, " +
        "login ${config.loginHost}:${config.loginPort})"
  }
  Runtime.getRuntime().addShutdownHook(Thread { gateway.close() })
  gateway.closeFuture()?.sync()
}

private fun parseArgs(args: Array<String>): Map<String, String> {
  val out = mutableMapOf<String, String>()
  var i = 0
  while (i < args.size) {
    val a = args[i]
    if (a == "--help" || a == "-h") usage(null)
    if (!a.startsWith("--") || i + 1 >= args.size) usage("bad argument: $a")
    out[a.removePrefix("--")] = args[i + 1]
    i += 2
  }
  return out
}

private fun usage(msg: String?): Nothing {
  if (msg != null) System.err.println(msg)
  System.err.println(
      "usage: web.gateway [--host 127.0.0.1] [--port 8080] [--static web/static] " +
          "[--login-host 127.0.0.1] [--login-port 2106] [--key keys/build/game.public.pem] " +
          "[--decomp decomp] [--allow-origin URL,...] [--game-host HOST] " +
          "[--trust-forwarded true|false]")
  exitProcess(if (msg == null) 0 else 2)
}
