package de.fiereu.openmmo.server.game.services.social

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Settings for chat, trading, the market and guilds. Read from a system property (`-Dkey=value`)
 * first, then the environment variable named in the table below, then the default.
 *
 * | property             | env                      | default |
 * |----------------------|--------------------------|---------|
 * | trade.proximity      | TRADE_PROXIMITY          | map     | `none`, `map` or a tile count |
 * | trade.requestSeconds | TRADE_REQUEST_SECONDS    | 60      |
 * | market.feePermille   | MARKET_FEE_PERMILLE      | 20      | 2 % of the asking price       |
 * | market.minFee        | MARKET_MIN_FEE           | 1       |
 * | market.maxActive     | MARKET_MAX_ACTIVE        | 30      | active listings per character |
 * | chat.maxLength       | CHAT_MAX_LENGTH          | 200     |
 * | chat.filterFile      | OPENMMO_CHAT_FILTER_FILE | (none)  | one word per line             |
 * | guild.foundCost      | GUILD_FOUND_COST         | 15000   |
 * | guild.maxMembers     | GUILD_MAX_MEMBERS        | 50      |
 * | rate.<kind>          | RATE_<KIND>              | below   | `events/seconds`, e.g. `8/10` |
 */
data class SocialConfig(
    val tradeProximity: TradeProximity = TradeProximity.SameMap,
    val tradeRequestSeconds: Int = 60,
    val marketFeePermille: Int = 20,
    val marketMinFee: Int = 1,
    val marketMaxActive: Int = 30,
    val chatMaxLength: Int = 200,
    val chatFilterWords: List<String> = emptyList(),
    val guildFoundCost: Int = 15000,
    val guildMaxMembers: Int = 50,
    val rates: Map<RateKind, Rate> = RateKind.entries.associateWith { it.default },
) {
  /** The fee for listing at [price]: per mille of the price, at least [marketMinFee]. */
  fun listingFee(price: Int): Int {
    if (marketFeePermille <= 0) return 0
    val fee = (price.toLong() * marketFeePermille / 1000).coerceAtMost(Int.MAX_VALUE.toLong())
    return maxOf(marketMinFee, fee.toInt())
  }

  companion object {
    fun fromEnvironment(
        prop: (String) -> String? = System::getProperty,
        env: (String) -> String? = System::getenv,
    ): SocialConfig {
      fun read(key: String, envKey: String): String? =
          prop(key)?.takeIf { it.isNotBlank() } ?: env(envKey)?.takeIf { it.isNotBlank() }
      fun int(key: String, envKey: String, default: Int): Int =
          read(key, envKey)?.trim()?.toIntOrNull() ?: default
      val filterWords =
          read("chat.filterFile", "OPENMMO_CHAT_FILTER_FILE")
              ?.let(::File)
              ?.takeIf { it.isFile }
              ?.readLines()
              ?.map { it.trim() }
              ?.filter { it.isNotEmpty() && !it.startsWith("#") }
              .orEmpty()
      return SocialConfig(
          tradeProximity = TradeProximity.parse(read("trade.proximity", "TRADE_PROXIMITY")),
          tradeRequestSeconds = int("trade.requestSeconds", "TRADE_REQUEST_SECONDS", 60),
          marketFeePermille = int("market.feePermille", "MARKET_FEE_PERMILLE", 20),
          marketMinFee = int("market.minFee", "MARKET_MIN_FEE", 1),
          marketMaxActive = int("market.maxActive", "MARKET_MAX_ACTIVE", 30),
          chatMaxLength = int("chat.maxLength", "CHAT_MAX_LENGTH", 200),
          chatFilterWords = filterWords,
          guildFoundCost = int("guild.foundCost", "GUILD_FOUND_COST", 15000),
          guildMaxMembers = int("guild.maxMembers", "GUILD_MAX_MEMBERS", 50),
          rates =
              RateKind.entries.associateWith { kind ->
                Rate.parse(read("rate.${kind.key}", "RATE_${kind.name}")) ?: kind.default
              },
      )
    }
  }
}

/** Where a trade partner has to be. */
sealed interface TradeProximity {
  data object Anywhere : TradeProximity

  data object SameMap : TradeProximity

  /** Same map and at most [tiles] steps apart (Manhattan distance). */
  data class Within(val tiles: Int) : TradeProximity

  companion object {
    fun parse(s: String?): TradeProximity =
        when (val v = s?.trim()?.lowercase()) {
          null,
          "",
          "map" -> SameMap
          "none",
          "off",
          "any" -> Anywhere
          else -> v.toIntOrNull()?.takeIf { it >= 0 }?.let(::Within) ?: SameMap
        }
  }
}

/** At most [events] in any [seconds] window (a token bucket of that size). */
data class Rate(val events: Int, val seconds: Int) {
  companion object {
    fun parse(s: String?): Rate? {
      val parts = s?.split('/') ?: return null
      if (parts.size != 2) return null
      val e = parts[0].trim().toIntOrNull() ?: return null
      val sec = parts[1].trim().toIntOrNull() ?: return null
      return if (e > 0 && sec > 0) Rate(e, sec) else null
    }
  }
}

enum class RateKind(val key: String, val default: Rate) {
  CHAT("chat", Rate(8, 10)),
  WHISPER("whisper", Rate(10, 10)),
  TRADE_REQUEST("tradeRequest", Rate(5, 60)),
  MARKET("market", Rate(30, 10)),
  SOCIAL("social", Rate(20, 10)),
}

/**
 * Per-account token buckets. Keyed by the login account (user id) rather than the character, so
 * switching characters does not reset a limit.
 */
class RateLimiter(
    private val rates: Map<RateKind, Rate>,
    private val clock: () -> Long = System::nanoTime,
) {
  private class Bucket(var tokens: Double, var at: Long)

  private val buckets = ConcurrentHashMap<Pair<Int, RateKind>, Bucket>()

  /** Takes one token; false when [userId] is over the limit for [kind]. */
  fun tryAcquire(userId: Int, kind: RateKind): Boolean {
    val rate = rates[kind] ?: kind.default
    val now = clock()
    val bucket = buckets.computeIfAbsent(userId to kind) { Bucket(rate.events.toDouble(), now) }
    synchronized(bucket) {
      val perNano = rate.events.toDouble() / (rate.seconds * 1_000_000_000.0)
      bucket.tokens = minOf(rate.events.toDouble(), bucket.tokens + (now - bucket.at) * perNano)
      bucket.at = now
      if (bucket.tokens < 1.0) return false
      bucket.tokens -= 1.0
      return true
    }
  }

  fun forget(userId: Int) {
    buckets.keys.removeIf { it.first == userId }
  }
}

/**
 * What a chat line goes through before anyone sees it. Returns the text to deliver, or null to drop
 * it. The default ([WordListChatFilter] with no words) changes nothing; it is the hook for a real
 * filter.
 */
fun interface ChatFilter {
  fun apply(text: String): String?
}

/** Masks whole-word, case-insensitive matches of [words] with asterisks. */
class WordListChatFilter(words: Collection<String>) : ChatFilter {
  private val pattern: Regex? =
      words
          .filter { it.isNotBlank() }
          .takeIf { it.isNotEmpty() }
          ?.joinToString("|", "\\b(?:", ")\\b") { Regex.escape(it.trim()) }
          ?.let { Regex(it, RegexOption.IGNORE_CASE) }

  override fun apply(text: String): String? =
      pattern?.replace(text) { "*".repeat(it.value.length) } ?: text
}

/**
 * Normalises player text: drops control characters, collapses runs of whitespace and trims. Null
 * when nothing is left.
 */
fun cleanText(text: String): String? =
    text.filter { !it.isISOControl() }.replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }
