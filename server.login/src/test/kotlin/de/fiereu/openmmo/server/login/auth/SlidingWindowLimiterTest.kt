package de.fiereu.openmmo.server.login.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SlidingWindowLimiterTest :
    FunSpec({
      test("allows max events per window and frees them as the window slides") {
        val l = SlidingWindowLimiter(max = 2, windowMillis = 1000)
        l.tryAcquire("a", 0) shouldBe true
        l.tryAcquire("a", 100) shouldBe true
        l.tryAcquire("a", 200) shouldBe false
        l.tryAcquire("b", 200) shouldBe true
        l.retryAt("a", 200) shouldBe 1000
        l.tryAcquire("a", 1000) shouldBe true
      }

      test("sweep drops drained keys") {
        val l = SlidingWindowLimiter(max = 1, windowMillis = 10)
        l.record("x", 0)
        l.isLimited("x", 5) shouldBe true
        l.sweep(100)
        l.isLimited("x", 100) shouldBe false
      }
    })
