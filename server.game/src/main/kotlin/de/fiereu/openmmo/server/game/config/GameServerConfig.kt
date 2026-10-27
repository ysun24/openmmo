package de.fiereu.openmmo.server.game.config

import java.time.Duration

data class GameServerConfig(
    val host: String,
    val port: Int,
    val checksumSize: Int,
    val rootKeyResource: String,
    val sessionSecret: ByteArray,
    val sessionTokenMaxAge: Duration = Duration.ofMinutes(5),
    val db: DbConfig = DbConfig(),
    val rootKey: String? = null,
    val rootKeyFile: String? = null,
    /**
     * Login user ids whose characters are admins. The login server's bootstrap account is the
     * first user of an empty database, so this defaults to user 1.
     */
    val adminUserIds: Set<Int> = setOf(1),
) {
  override fun equals(other: Any?): Boolean =
      other is GameServerConfig &&
          host == other.host &&
          port == other.port &&
          checksumSize == other.checksumSize &&
          rootKeyResource == other.rootKeyResource &&
          rootKey == other.rootKey &&
          rootKeyFile == other.rootKeyFile &&
          sessionSecret.contentEquals(other.sessionSecret) &&
          sessionTokenMaxAge == other.sessionTokenMaxAge &&
          db == other.db &&
          adminUserIds == other.adminUserIds

  override fun hashCode(): Int {
    var h = host.hashCode()
    h = h * 31 + port
    h = h * 31 + checksumSize
    h = h * 31 + rootKeyResource.hashCode()
    h = h * 31 + rootKey.hashCode()
    h = h * 31 + rootKeyFile.hashCode()
    h = h * 31 + sessionSecret.contentHashCode()
    h = h * 31 + sessionTokenMaxAge.hashCode()
    h = h * 31 + db.hashCode()
    h = h * 31 + adminUserIds.hashCode()
    return h
  }
}
