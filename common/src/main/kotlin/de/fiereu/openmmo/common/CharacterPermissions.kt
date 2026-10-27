package de.fiereu.openmmo.common

object CharacterPermissions {
  // The codec sends only the low byte, so bits above 0xFF never reach the client.
  const val DEVELOPER = 0x100
  /** May grant DEVELOPER/ADMIN to other characters (`/grant`). Server-side only, like DEVELOPER. */
  const val ADMIN = 0x200
}

fun CharacterInfo.hasPermission(permission: Int): Boolean = permissions and permission == permission
