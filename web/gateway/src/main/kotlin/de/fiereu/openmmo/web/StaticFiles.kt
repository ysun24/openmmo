package de.fiereu.openmmo.web

import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Resolving request paths under the static root and picking content types and cache headers. */
class StaticFiles(root: Path) {
  val root: Path = root.toAbsolutePath().normalize()
  private val realRoot: Path? = runCatching { this.root.toRealPath() }.getOrNull()

  /**
   * The file a request path names, or null when it is outside the root, hidden, or does not exist.
   * A directory resolves to its index.html.
   */
  fun resolve(rawUri: String): Path? {
    val pathPart = rawUri.substringBefore('?').substringBefore('#')
    val decoded =
        runCatching { URLDecoder.decode(pathPart.replace("+", "%2B"), StandardCharsets.UTF_8) }
            .getOrNull() ?: return null
    if (!decoded.startsWith("/") || decoded.contains('\u0000') || decoded.contains('\\'))
        return null
    val segments = decoded.split('/').filter { it.isNotEmpty() }
    // No "..", no "." and no dot files at all, so nothing like .git or ../ is ever reachable.
    if (segments.any { it.startsWith(".") }) return null
    var p = root
    for (s in segments) p = p.resolve(s)
    p = p.normalize()
    if (!p.startsWith(root)) return null
    if (Files.isDirectory(p)) p = p.resolve("index.html")
    if (!Files.isRegularFile(p)) return null
    // Symlinks pointing out of the root are refused too.
    val real = runCatching { p.toRealPath() }.getOrNull() ?: return null
    if (realRoot == null || !real.startsWith(realRoot)) return null
    return p
  }

  fun cacheControl(file: Path): String {
    val rel = root.relativize(file).toString().replace('\\', '/')
    return if (rel.startsWith("assets/")) "public, max-age=604800" else "no-cache"
  }

  companion object {
    private val MIME =
        mapOf(
            "html" to "text/html; charset=utf-8",
            "htm" to "text/html; charset=utf-8",
            "js" to "text/javascript; charset=utf-8",
            "mjs" to "text/javascript; charset=utf-8",
            "css" to "text/css; charset=utf-8",
            "json" to "application/json; charset=utf-8",
            "map" to "application/json; charset=utf-8",
            "txt" to "text/plain; charset=utf-8",
            "png" to "image/png",
            "gif" to "image/gif",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "webp" to "image/webp",
            "svg" to "image/svg+xml",
            "ico" to "image/x-icon",
            "bin" to "application/octet-stream",
            "wasm" to "application/wasm",
            "woff" to "font/woff",
            "woff2" to "font/woff2",
            "ttf" to "font/ttf",
            "ogg" to "audio/ogg",
            "mp3" to "audio/mpeg",
            "wav" to "audio/wav",
            "mid" to "audio/midi",
            "webmanifest" to "application/manifest+json",
        )

    fun mime(file: Path): String =
        MIME[file.fileName.toString().substringAfterLast('.', "").lowercase()]
            ?: "application/octet-stream"

    val HTTP_DATE: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)
  }
}

/** Serves GET/HEAD for everything that is not the WebSocket upgrade. */
class StaticFileHandler(private val files: StaticFiles) :
    SimpleChannelInboundHandler<FullHttpRequest>() {

  override fun channelRead0(ctx: ChannelHandlerContext, req: FullHttpRequest) {
    if (!req.decoderResult().isSuccess) return send(ctx, req, error(HttpResponseStatus.BAD_REQUEST))
    if (req.method() != HttpMethod.GET && req.method() != HttpMethod.HEAD) {
      return send(
          ctx,
          req,
          error(HttpResponseStatus.METHOD_NOT_ALLOWED).also {
            it.headers().set(HttpHeaderNames.ALLOW, "GET, HEAD")
          })
    }
    val file =
        files.resolve(req.uri()) ?: return send(ctx, req, error(HttpResponseStatus.NOT_FOUND))
    val modified = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant()
    val lastModified = StaticFiles.HTTP_DATE.format(modified)
    val since = req.headers().get(HttpHeaderNames.IF_MODIFIED_SINCE)
    val notModified =
        since != null &&
            runCatching {
                  !Instant.from(StaticFiles.HTTP_DATE.parse(since))
                      .isBefore(modified.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
                }
                .getOrDefault(false)
    val res: FullHttpResponse =
        if (notModified) {
          DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_MODIFIED)
        } else {
          val bytes = Files.readAllBytes(file)
          DefaultFullHttpResponse(
                  HttpVersion.HTTP_1_1,
                  HttpResponseStatus.OK,
                  if (req.method() == HttpMethod.HEAD) Unpooled.EMPTY_BUFFER
                  else Unpooled.wrappedBuffer(bytes))
              .also {
                it.headers().set(HttpHeaderNames.CONTENT_TYPE, StaticFiles.mime(file))
                HttpUtil.setContentLength(it, bytes.size.toLong())
              }
        }
    res.headers().set(HttpHeaderNames.LAST_MODIFIED, lastModified)
    res.headers().set(HttpHeaderNames.CACHE_CONTROL, files.cacheControl(file))
    send(ctx, req, res)
  }

  private fun error(status: HttpResponseStatus): FullHttpResponse {
    val body = "${status.code()} ${status.reasonPhrase()}\n".toByteArray()
    return DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body))
        .also {
          it.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8")
          HttpUtil.setContentLength(it, body.size.toLong())
        }
  }

  private fun send(ctx: ChannelHandlerContext, req: FullHttpRequest, res: FullHttpResponse) {
    res.headers().set("X-Content-Type-Options", "nosniff")
    res.headers().set("Referrer-Policy", "no-referrer")
    if (!res.headers().contains(HttpHeaderNames.CONTENT_LENGTH)) HttpUtil.setContentLength(res, 0)
    val keepAlive = HttpUtil.isKeepAlive(req) && res.status().code() < 400
    if (keepAlive) {
      res.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
      ctx.writeAndFlush(res)
    } else {
      res.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
      ctx.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE)
    }
  }

  override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    ctx.close()
  }
}
