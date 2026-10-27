package de.fiereu.openmmo.server.login.auth

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Counts events per key in a sliding window: at most [max] within [windowMillis]. Keys whose window
 * has drained are dropped on the next [sweep], so the map cannot grow without bound from keys that
 * were seen once.
 */
class SlidingWindowLimiter(
    private val max: Int,
    private val windowMillis: Long,
    private val maxKeys: Int = 100_000,
) {
  private val hits = ConcurrentHashMap<String, ArrayDeque<Long>>()

  /** Records an event for [key] when it is under the limit. Returns false when it is not. */
  fun tryAcquire(key: String, now: Long): Boolean {
    if (hits.size > maxKeys) sweep(now)
    val q = hits.computeIfAbsent(key) { ArrayDeque() }
    synchronized(q) {
      drain(q, now)
      if (q.size >= max) return false
      q.addLast(now)
      return true
    }
  }

  /** Records an event without checking the limit (e.g. a failure after the fact). */
  fun record(key: String, now: Long) {
    val q = hits.computeIfAbsent(key) { ArrayDeque() }
    synchronized(q) {
      drain(q, now)
      q.addLast(now)
    }
  }

  fun isLimited(key: String, now: Long): Boolean {
    val q = hits[key] ?: return false
    synchronized(q) {
      drain(q, now)
      return q.size >= max
    }
  }

  /** Epoch millis at which [key] may act again, or [now] when it is not limited. */
  fun retryAt(key: String, now: Long): Long {
    val q = hits[key] ?: return now
    synchronized(q) {
      drain(q, now)
      if (q.size < max) return now
      return q.peekFirst() + windowMillis
    }
  }

  fun reset(key: String) {
    hits.remove(key)
  }

  fun sweep(now: Long) {
    hits.entries.removeIf { (_, q) ->
      synchronized(q) {
        drain(q, now)
        q.isEmpty()
      }
    }
  }

  private fun drain(q: ArrayDeque<Long>, now: Long) {
    while (q.isNotEmpty() && q.peekFirst() <= now - windowMillis) q.removeFirst()
  }
}

/**
 * Login and registration throttling shared by every connection.
 *
 * Behind the web gateway (and a tunnel in front of it) every request comes from the same address,
 * so the address limits are generous and the real protection is per account: a username with too
 * many failed passwords is locked for the rest of the window, whoever asks. Per-connection limits
 * live in the handler.
 */
@Singleton
class LoginThrottle
internal constructor(
    /** Failed password logins per username before it is locked. */
    private val failuresPerUser: SlidingWindowLimiter,
    /** Login attempts per remote address. */
    private val loginsPerAddress: SlidingWindowLimiter,
    /** Registrations per remote address. */
    private val registrationsPerAddress: SlidingWindowLimiter,
) {
  @Inject
  constructor() :
      this(
          SlidingWindowLimiter(10, 15 * MINUTE),
          SlidingWindowLimiter(120, MINUTE),
          SlidingWindowLimiter(20, HOUR),
      )

  fun loginAllowed(username: String, address: String, now: Long): Boolean =
      !failuresPerUser.isLimited(username.lowercase(), now) &&
          loginsPerAddress.tryAcquire(address, now)

  fun loginRetryAt(username: String, address: String, now: Long): Long =
      maxOf(
          failuresPerUser.retryAt(username.lowercase(), now),
          loginsPerAddress.retryAt(address, now))

  fun loginFailed(username: String, now: Long) = failuresPerUser.record(username.lowercase(), now)

  fun loginSucceeded(username: String) = failuresPerUser.reset(username.lowercase())

  fun registrationAllowed(address: String, now: Long): Boolean =
      registrationsPerAddress.tryAcquire(address, now)

  companion object {
    const val MINUTE = 60_000L
    const val HOUR = 60 * MINUTE
  }
}
