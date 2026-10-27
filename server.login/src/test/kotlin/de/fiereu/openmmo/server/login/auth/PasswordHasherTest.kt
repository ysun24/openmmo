package de.fiereu.openmmo.server.login.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith

class PasswordHasherTest :
    FunSpec({
      val hasher = PasswordHasher(iterations = 1_000)
      val digest = sha1Hex("pikachu123")

      test("a hash verifies its own digest and nothing else") {
        val encoded = hasher.hash(digest)
        encoded shouldStartWith "1000$"
        hasher.verify(digest, encoded) shouldBe true
        hasher.verify(sha1Hex("pikachu124"), encoded) shouldBe false
      }

      test("every hash gets its own salt") { hasher.hash(digest) shouldNotBe hasher.hash(digest) }

      test("malformed stored values never verify") {
        for (bad in
            listOf(
                "",
                "x",
                "1$$",
                "abc\$AAAA\$AAAA",
                "0\$AAAA\$AAAA",
                "1000\$!!\$AAAA",
                "1000\$AAAA$")) hasher.verify(digest, bad) shouldBe false
      }

      test("a raw sha1 row is not a valid pbkdf2 value") {
        hasher.verify(digest, digest) shouldBe false
      }

      test("needsRehash when the stored count is below the current one") {
        PasswordHasher(iterations = 2_000).needsRehash(hasher.hash(digest)) shouldBe true
        hasher.needsRehash(hasher.hash(digest)) shouldBe false
      }

      test("the default iteration count is at least 210k") {
        (PasswordHasher.DEFAULT_ITERATIONS >= 210_000) shouldBe true
        PasswordHasher().hash(digest) shouldStartWith "${PasswordHasher.DEFAULT_ITERATIONS}$"
      }

      test("constantTimeEquals") {
        PasswordHasher.constantTimeEquals(digest, sha1Hex("pikachu123")) shouldBe true
        PasswordHasher.constantTimeEquals(digest, digest.dropLast(1)) shouldBe false
      }
    })
