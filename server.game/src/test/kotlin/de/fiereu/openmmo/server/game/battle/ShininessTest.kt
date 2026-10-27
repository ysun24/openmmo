package de.fiereu.openmmo.server.game.battle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class ShininessTest :
    FunSpec({
      test("Gen 3 rule: TID ^ SID ^ PID high ^ PID low below 8") {
        // otId 0: the personality's halves alone decide
        Shininess.isShiny(0x00000007, 0) shouldBe true
        Shininess.isShiny(0x00000008, 0) shouldBe false
        Shininess.isShiny(0x00010006, 0) shouldBe true // 1 ^ 6 = 7
        Shininess.isShiny(0x00010008, 0) shouldBe false // 1 ^ 8 = 9
        // TID 0x5678, SID 0x1234: a PID whose halves xor to 0x1234 ^ 0x5678 is shiny
        val otId = 0x12345678
        Shininess.isShiny((0x444C shl 16) or 0x0000, otId) shouldBe true
        Shininess.isShiny((0x444C shl 16) or 0x0007, otId) shouldBe true
        Shininess.isShiny((0x444C shl 16) or 0x0008, otId) shouldBe false
        // unsigned halves: a negative personality (high bit set) still works
        Shininess.isShiny(0xFFFF0000.toInt() or 0xFFFF, 0) shouldBe true
      }

      test("about 1 in 8192 random personalities are shiny") {
        val r = java.util.Random(7)
        val otId = Shininess.otId(123456789L)
        val n = 4_000_000
        var hits = 0
        repeat(n) { if (Shininess.isShiny(r.nextInt(), otId)) hits++ }
        (hits.toDouble() / n * 8192).shouldBeBetween(0.85, 1.15, 0.0)
      }

      test("a character's OT id is stable and differs between characters") {
        Shininess.otId(42L) shouldBe Shininess.otId(42L)
        Shininess.otId(42L) shouldNotBe Shininess.otId(43L)
        Shininess.trainerId(42L) shouldBe (Shininess.otId(42L) and 0xFFFF)
      }

      test("a forced shiny personality is shiny for that OT and keeps the nature") {
        val r = java.util.Random(1)
        for (nature in 0 until 25) {
          val otId = Shininess.otId(1000L + nature)
          val pid = Shininess.shinyPersonality(otId, nature, r)
          Shininess.isShiny(pid, otId) shouldBe true
          ((pid.toLong() and 0xFFFFFFFFL) % 25).toInt() shouldBe nature
        }
      }
    })
