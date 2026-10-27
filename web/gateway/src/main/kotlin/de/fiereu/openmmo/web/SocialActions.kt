package de.fiereu.openmmo.web

/**
 * r3-social browser actions (PROTOCOL.md "r3-social"). Part of [GameActions]; the defaults let a
 * test fake implement only what it records.
 */
interface SocialActions {
  /** Chat on a channel: local, global, trade, guild or whisper (with [to]). */
  fun sayOn(channel: String, to: String?, text: String): String? = UNSUPPORTED

  fun socialRefresh(): String? = UNSUPPORTED

  fun friendAdd(name: String): String? = UNSUPPORTED

  fun friendRemove(name: String): String? = UNSUPPORTED

  fun block(name: String): String? = UNSUPPORTED

  fun unblock(name: String): String? = UNSUPPORTED

  fun profile(name: String): String? = UNSUPPORTED

  fun guildCreate(name: String, tag: String): String? = UNSUPPORTED

  fun guildInvite(name: String): String? = UNSUPPORTED

  fun guildAnswer(accept: Boolean): String? = UNSUPPORTED

  fun guildLeave(): String? = UNSUPPORTED

  fun guildKick(name: String): String? = UNSUPPORTED

  fun guildRank(name: String, rank: Int): String? = UNSUPPORTED

  fun guildMotd(text: String): String? = UNSUPPORTED

  fun guildDisband(): String? = UNSUPPORTED

  fun tradePcMon(slot: Int): String? = UNSUPPORTED

  fun tradeHistory(): String? = UNSUPPORTED

  fun marketHistory(pokemon: Boolean, id: Int): String? = UNSUPPORTED

  fun marketSales(): String? = UNSUPPORTED

  fun marketInfo(): String? = UNSUPPORTED

  companion object {
    const val UNSUPPORTED = "not supported"
    val CHANNELS = setOf("local", "global", "trade", "guild", "whisper")
  }
}
