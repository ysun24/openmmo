package de.fiereu.openmmo.web

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransportTest {
  private val tmp = Files.createTempDirectory("static")
  private val root = tmp.resolve("static").createDirectories()

  init {
    root.resolve("index.html").writeText("<p>hi</p>")
    root.resolve("js").createDirectories().resolve("main.js").writeText("export {}")
    root.resolve("assets/maps").createDirectories().resolve("0_3_0.bottom.png").writeText("png")
    root.resolve(".env").writeText("SECRET=1")
    tmp.resolve("outside.txt").writeText("no")
    Files.createSymbolicLink(root.resolve("escape.txt"), tmp.resolve("outside.txt"))
  }

  private val files = StaticFiles(root)

  @Test
  fun `serves files under the root and index for directories`() {
    assertEquals(root.resolve("index.html"), files.resolve("/"))
    assertEquals(root.resolve("index.html"), files.resolve("/index.html?v=3"))
    assertEquals(root.resolve("js/main.js"), files.resolve("/js/main.js"))
    assertEquals(root.resolve("js/main.js"), files.resolve("/js/%6Dain.js"))
    assertNotNull(files.resolve("/assets/maps/0_3_0.bottom.png"))
  }

  @Test
  fun `refuses traversal, dot files, symlinks out and odd paths`() {
    for (p in
        listOf(
            "/../outside.txt",
            "/js/../../outside.txt",
            "/%2e%2e/outside.txt",
            "/%2E%2E%2Foutside.txt",
            "/.env",
            "/js/..%5c..%5coutside.txt",
            "/escape.txt",
            "/missing.js",
            "/js/main.js%00.png",
            "relative",
            "/%zz")) {
      assertNull(files.resolve(p), p)
    }
  }

  @Test
  fun `mime types and cache headers`() {
    assertEquals("text/html; charset=utf-8", StaticFiles.mime(root.resolve("index.html")))
    assertEquals("text/javascript; charset=utf-8", StaticFiles.mime(root.resolve("js/main.js")))
    assertEquals("image/png", StaticFiles.mime(root.resolve("a.PNG")))
    assertEquals("application/octet-stream", StaticFiles.mime(root.resolve("maps/x.col.bin")))
    assertEquals("no-cache", files.cacheControl(root.resolve("js/main.js")))
    assertEquals("no-cache", files.cacheControl(root.resolve("index.html")))
    assertTrue(files.cacheControl(root.resolve("assets/maps/0_3_0.bottom.png")).contains("max-age"))
  }

  @Test
  fun `origin must match the host`() {
    assertTrue(OriginGuard.originAllowed(null, "127.0.0.1:8080", emptySet()))
    assertTrue(OriginGuard.originAllowed("http://127.0.0.1:8080", "127.0.0.1:8080", emptySet()))
    assertTrue(OriginGuard.originAllowed("http://LOCALHOST:8080", "localhost:8080", emptySet()))
    assertFalse(OriginGuard.originAllowed("http://evil.example", "127.0.0.1:8080", emptySet()))
    assertFalse(OriginGuard.originAllowed("http://127.0.0.1:9999", "127.0.0.1:8080", emptySet()))
    assertFalse(OriginGuard.originAllowed("null", "127.0.0.1:8080", emptySet()))
    assertFalse(OriginGuard.originAllowed("http://127.0.0.1:8080", null, emptySet()))
    assertTrue(
        OriginGuard.originAllowed(
            "https://game.example", "127.0.0.1:8080", setOf("https://game.example")))
  }

  @Test
  fun `login rate limit is five a minute`() {
    val r = RateLimiter(5, 60_000)
    repeat(5) { assertTrue(r.tryAcquire(1000L + it)) }
    assertFalse(r.tryAcquire(2000))
    assertTrue(r.tryAcquire(61_001))
  }

  @Test
  fun `login error codes`() {
    assertEquals("INVALID_PASSWORD", WsSession.loginErrorCode("login refused: INVALID_PASSWORD"))
    assertEquals("NO_JOINABLE_GAME_SERVER", WsSession.loginErrorCode("no joinable game server"))
    assertEquals("LOGIN_FAILED", WsSession.loginErrorCode(null))
  }
}
