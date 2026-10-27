package de.fiereu.openmmo.server.game.battle

/** Per-viewer monster names in battle lines (a duel shows the same line to both players). */
object BattleNames {
  private val TOKEN = Regex("""\{\{mon:(\d+)}}""")

  fun token(mon: BattleMonState): String = "{{mon:${mon.entityId}}}"

  /** The line as [battle]'s player reads it: own monsters bare, the others "Foe" / "Wild". */
  fun localize(battle: BattleInstance, text: String): String =
      TOKEN.replace(text) { m ->
        val id = m.groupValues[1].toLong()
        val own = battle.party.firstOrNull { it.entityId == id }
        if (own != null) return@replace own.source.nickname.ifEmpty { own.species.name }
        val foe = battle.opponent.firstOrNull { it.entityId == id } ?: return@replace "?"
        val base = foe.source.nickname.ifEmpty { foe.species.name }
        if (battle.trainer == null && battle.pvp == null) "Wild $base" else "Foe $base"
      }
}
