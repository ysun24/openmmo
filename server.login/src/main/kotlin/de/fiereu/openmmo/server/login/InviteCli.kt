package de.fiereu.openmmo.server.login

import de.fiereu.openmmo.server.login.auth.JooqUserStore
import de.fiereu.openmmo.server.login.auth.RegistrationRules
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * `server.login invite <create|list|revoke>`: invite codes for self registration, run against the
 * configured database (inside the container: `docker compose exec login bin/server.login invite
 * create --uses 1 --days 7`).
 */
internal object InviteCli {
  private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
  private val random = SecureRandom()

  fun usage(): String =
      """
      usage: server.login invite create [--uses N] [--days D] [--by NAME] [--count C]
             server.login invite list
             server.login invite revoke CODE
      """
          .trimIndent()

  /** Returns the process exit code. */
  fun run(args: List<String>, store: JooqUserStore, out: (String) -> Unit = ::println): Int {
    when (args.firstOrNull()) {
      "create" -> {
        val opts = options(args.drop(1)) ?: return usageError(out)
        val uses = opts["uses"]?.toIntOrNull() ?: 1
        val days = opts["days"]?.toLongOrNull() ?: 7
        val count = opts["count"]?.toIntOrNull() ?: 1
        val by = opts["by"] ?: "admin"
        if (uses !in 1..1000 || days !in 0..3650 || count !in 1..100) return usageError(out)
        val expires = if (days == 0L) null else LocalDateTime.now(ZoneOffset.UTC).plusDays(days)
        repeat(count) {
          val code = newCode()
          store.createInvite(code, by.take(32), uses, expires)
          out(code)
        }
        out(
            "# ${count} code(s), $uses use(s) each, " +
                (expires?.let { "expire $it UTC" } ?: "never expire"))
        return 0
      }
      "list" -> {
        val invites = store.activeInvites()
        out("CODE            USES   EXPIRES (UTC)         BY")
        for (i in invites) {
          out(
              "%-15s %-6s %-21s %s"
                  .format(
                      i.code,
                      "${i.uses}/${i.maxUses}",
                      i.expiresAt?.withNano(0)?.toString() ?: "never",
                      i.createdBy))
        }
        out("# ${invites.size} active")
        return 0
      }
      "revoke" -> {
        val code = args.getOrNull(1) ?: return usageError(out)
        val ok = store.revokeInvite(code)
        out(if (ok) "revoked ${RegistrationRules.normalizeInvite(code)}" else "no such code")
        return if (ok) 0 else 1
      }
      else -> return usageError(out)
    }
  }

  fun newCode(): String =
      (0 until 3).joinToString("-") {
        (0 until 4).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
      }

  private fun options(args: List<String>): Map<String, String>? {
    if (args.size % 2 != 0) return null
    return args.chunked(2).associate { (k, v) ->
      if (!k.startsWith("--")) return null
      k.removePrefix("--") to v
    }
  }

  private fun usageError(out: (String) -> Unit): Int {
    out(usage())
    return 2
  }
}
