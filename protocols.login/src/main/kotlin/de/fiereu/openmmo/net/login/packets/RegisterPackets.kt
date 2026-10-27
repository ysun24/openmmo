package de.fiereu.openmmo.net.login.packets

import de.fiereu.bytecodec.CodecScope
import de.fiereu.bytecodec.PacketCodec
import de.fiereu.bytecodec.U8
import de.fiereu.bytecodec.Utf16LeNullTerminated

/**
 * OpenMMO-only: creates an account with an invite code. Retail has no registration packet (accounts
 * are made on the website), so this pair lives in [OpenMmoLoginOpcodes], away from retail opcodes.
 *
 * [password] is the same SHA-1 hex digest a [PasswordLogin] carries, never the plain password.
 */
data class RegisterRequestPacket(
    val username: String,
    val password: String,
    val invite: String,
)

object RegisterRequestPacketCodec : PacketCodec<RegisterRequestPacket>() {
  override fun CodecScope<RegisterRequestPacket>.body(): RegisterRequestPacket {
    val username = field(Utf16LeNullTerminated) { it.username }
    val password = field(Utf16LeNullTerminated) { it.password }
    val invite = field(Utf16LeNullTerminated) { it.invite }
    return RegisterRequestPacket(username, password, invite)
  }
}

/** Outcome of a [RegisterRequestPacket]. Unknown ids decode as [SYSTEM_ERROR]. */
enum class RegisterResult(val id: Int) {
  OK(0),
  SYSTEM_ERROR(1),
  BAD_USERNAME(2),
  BAD_PASSWORD(3),
  USERNAME_TAKEN(4),
  BAD_INVITE(5),
  RATE_LIMITED(6),
  ALREADY_LOGGED_IN(7),
}

data class RegisterResponsePacket(val result: RegisterResult)

object RegisterResponsePacketCodec : PacketCodec<RegisterResponsePacket>() {
  override fun CodecScope<RegisterResponsePacket>.body(): RegisterResponsePacket {
    val id = field(U8) { it.result.id }
    return RegisterResponsePacket(
        RegisterResult.entries.find { it.id == id } ?: RegisterResult.SYSTEM_ERROR)
  }
}

/**
 * Opcodes OpenMMO adds to the login protocol. They sit at 0x70 and up, a range no retail login
 * packet uses (retail tops out at 0x26); [RETAIL_C2S] / [RETAIL_S2C] list what retail does use so a
 * test can keep the two apart.
 */
object OpenMmoLoginOpcodes {
  const val REGISTER: UByte = 0x70u

  val RETAIL_C2S: Set<UByte> = setOf(0x02u, 0x03u, 0x04u, 0x08u, 0x11u)
  val RETAIL_S2C: Set<UByte> = setOf(0x01u, 0x03u, 0x05u, 0x07u, 0x08u, 0x14u, 0x22u, 0x26u)
}
