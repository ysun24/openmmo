package de.fiereu.openmmo.server.login.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Stores what the client sends (the SHA-1 hex of the password) as PBKDF2-HMAC-SHA256 with a
 * per-user salt, so a leaked users table no longer is a list of ready-to-replay login secrets.
 *
 * Encoded form: `<iterations>$<base64 salt>$<base64 key>`, in a row whose `password_scheme` is
 * [SCHEME_PBKDF2]. Rows still on [SCHEME_SHA1] hold the raw client digest and are upgraded by the
 * store on their next successful login.
 */
class PasswordHasher(
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val random: SecureRandom = SecureRandom(),
) {
  init {
    require(iterations >= 1) { "iterations must be positive" }
  }

  fun hash(clientDigest: String): String {
    val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
    val key = derive(clientDigest, salt, iterations)
    val b64 = Base64.getEncoder().withoutPadding()
    return listOf(iterations.toString(), b64.encodeToString(salt), b64.encodeToString(key))
        .joinToString(SEPARATOR)
  }

  /** Constant time over the derived key. A malformed [encoded] value never matches. */
  fun verify(clientDigest: String, encoded: String): Boolean {
    val parts = encoded.split('$')
    if (parts.size != 3) return false
    val iter = parts[0].toIntOrNull()?.takeIf { it in 1..MAX_ITERATIONS } ?: return false
    val dec = Base64.getDecoder()
    val salt = runCatching { dec.decode(parts[1]) }.getOrNull() ?: return false
    val expected = runCatching { dec.decode(parts[2]) }.getOrNull() ?: return false
    if (expected.isEmpty()) return false
    val actual = derive(clientDigest, salt, iter, expected.size * 8)
    return MessageDigest.isEqual(actual, expected)
  }

  /**
   * True when [encoded] used fewer iterations than this hasher would now, so it should be redone.
   */
  fun needsRehash(encoded: String): Boolean =
      (encoded.substringBefore('$').toIntOrNull() ?: 0) < iterations

  /** Burns the same time as a real [verify], for logins of unknown users. */
  fun dummyVerify(clientDigest: String) {
    derive(clientDigest, DUMMY_SALT, iterations)
  }

  companion object {
    const val SCHEME_SHA1 = "sha1"
    const val SCHEME_PBKDF2 = "pbkdf2-sha256"

    /** OWASP's 2023 floor for PBKDF2-HMAC-SHA256. */
    const val DEFAULT_ITERATIONS = 210_000
    private const val SEPARATOR = "$"
    private const val MAX_ITERATIONS = 10_000_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256
    private val DUMMY_SALT = ByteArray(SALT_BYTES)

    private fun derive(
        secret: String,
        salt: ByteArray,
        iterations: Int,
        bits: Int = KEY_BITS,
    ): ByteArray {
      val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, bits)
      try {
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
      } finally {
        spec.clearPassword()
      }
    }

    /** Constant-time equality for the legacy SHA-1 hex rows. */
    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
  }
}
