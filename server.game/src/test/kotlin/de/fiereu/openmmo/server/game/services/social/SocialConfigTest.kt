package de.fiereu.openmmo.server.game.services.social

import de.fiereu.openmmo.maps.MapManager
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class SocialConfigTest :
    FunSpec({
      test("listing fee: per mille of the price with a minimum, zero when disabled") {
        val c = SocialConfig(marketFeePermille = 20, marketMinFee = 5)
        c.listingFee(10_000) shouldBe 200
        c.listingFee(100) shouldBe 5
        c.listingFee(Int.MAX_VALUE) shouldBe (Int.MAX_VALUE.toLong() * 20 / 1000).toInt()
        SocialConfig(marketFeePermille = 0).listingFee(10_000) shouldBe 0
      }

      test("proximity and rates parse from strings, bad values fall back") {
        TradeProximity.parse(null) shouldBe TradeProximity.SameMap
        TradeProximity.parse("none") shouldBe TradeProximity.Anywhere
        TradeProximity.parse("3") shouldBe TradeProximity.Within(3)
        TradeProximity.parse("nonsense") shouldBe TradeProximity.SameMap
        Rate.parse("8/10") shouldBe Rate(8, 10)
        Rate.parse("0/10") shouldBe null
        Rate.parse("x") shouldBe null
      }

      test("settings come from properties first, then the environment") {
        val file = Files.createTempFile("filter", ".txt").toFile()
        file.writeText("# comment\nbadword\n\n")
        val props =
            mapOf("trade.proximity" to "none", "market.maxActive" to "5", "rate.chat" to "3/30")
        val env =
            mapOf(
                "MARKET_MAX_ACTIVE" to "99",
                "CHAT_MAX_LENGTH" to "120",
                "OPENMMO_CHAT_FILTER_FILE" to file.path)
        val c = SocialConfig.fromEnvironment(props::get, env::get)
        c.tradeProximity shouldBe TradeProximity.Anywhere
        c.marketMaxActive shouldBe 5
        c.chatMaxLength shouldBe 120
        c.rates[RateKind.CHAT] shouldBe Rate(3, 30)
        c.rates[RateKind.MARKET] shouldBe RateKind.MARKET.default
        c.chatFilterWords shouldBe listOf("badword")
        SocialConfig.fromEnvironment({ null }, { null }) shouldBe SocialConfig()
        file.delete()
      }

      test("rate limiter: a bucket per account and kind that refills over time") {
        var now = 0L
        val limiter = RateLimiter(mapOf(RateKind.CHAT to Rate(2, 10))) { now }
        limiter.tryAcquire(1, RateKind.CHAT) shouldBe true
        limiter.tryAcquire(1, RateKind.CHAT) shouldBe true
        limiter.tryAcquire(1, RateKind.CHAT) shouldBe false
        // Another account and another kind have their own budget.
        limiter.tryAcquire(2, RateKind.CHAT) shouldBe true
        limiter.tryAcquire(1, RateKind.WHISPER) shouldBe true
        now += 5_000_000_000L // half the window gives one token back
        limiter.tryAcquire(1, RateKind.CHAT) shouldBe true
        limiter.tryAcquire(1, RateKind.CHAT) shouldBe false
        now += 60_000_000_000L // never more than the bucket holds
        repeat(2) { limiter.tryAcquire(1, RateKind.CHAT) shouldBe true }
        limiter.tryAcquire(1, RateKind.CHAT) shouldBe false
      }

      test("word filter masks whole words only; the default changes nothing") {
        val f = WordListChatFilter(listOf("heck", "dang it"))
        f.apply("What the HECK, dang it!") shouldBe "What the ****, *******!"
        f.apply("checkers") shouldBe "checkers"
        WordListChatFilter(emptyList()).apply("heck") shouldBe "heck"
        cleanText(" a \t b\n") shouldBe "a b"
        cleanText("\u0000 ") shouldBe null
      }

      test("market clerks stand in a free lobby corner of every Pokemon Center") {
        val maps = MapManager()
        val oldale = maps.getMap(1, 52, 2).shouldNotBeNull()
        MarketClerks.findSpot(oldale) shouldBe (1 to 2)
        // Viridian's lobby has a youngster near the left corner.
        val viridian = maps.getMap(0, 5, 4).shouldNotBeNull()
        MarketClerks.findSpot(viridian) shouldBe (0 to 2)
        // A town map has no nurse and no clerk.
        MarketClerks.findSpot(maps.getMap(0, 3, 0).shouldNotBeNull()) shouldBe null
        val clerks = MarketClerks(maps)
        val spot = clerks.spotFor(1, 52, 2).shouldNotBeNull()
        clerks.isClerk(spot.entityId) shouldBe true
        clerks.isClerk(spot.entityId + 1) shouldBe false
      }

      test("every Pokemon Center gets a clerk") {
        val maps = MapManager()
        val missing = mutableListOf<String>()
        var centers = 0
        for (region in 0..1) for (bank in 0..100) for (map in 0..120) {
          val def = maps.getMap(region, bank, map) ?: continue
          if (def.npcs.none { it.script.endsWith("_EventScript_Nurse") }) continue
          centers++
          if (MarketClerks.findSpot(def) == null) missing += "$region:$bank:$map"
        }
        (centers > 20) shouldBe true
        missing shouldBe emptyList()
      }
    })
