package de.fiereu.openmmo.server.game.progression

import de.fiereu.openmmo.common.dialog.DialogLine
import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.server.game.battle.BattleResult
import de.fiereu.openmmo.server.game.script.ScriptContext
import de.fiereu.openmmo.server.game.services.notice

/** The badge names the games print, by region and number (1-8). */
object BadgeNames {
  val KANTO =
      listOf(
          "BOULDERBADGE",
          "CASCADEBADGE",
          "THUNDERBADGE",
          "RAINBOWBADGE",
          "SOULBADGE",
          "MARSHBADGE",
          "VOLCANOBADGE",
          "EARTHBADGE")
  val HOENN =
      listOf(
          "STONE BADGE",
          "KNUCKLE BADGE",
          "DYNAMO BADGE",
          "HEAT BADGE",
          "BALANCE BADGE",
          "FEATHER BADGE",
          "MIND BADGE",
          "RAIN BADGE")

  fun of(region: Region, badge: Int): String =
      (if (region == Region.HOENN) HOENN else KANTO).getOrElse(badge - 1) { "BADGE" }
}

private fun ScriptContext.regionKey(): String =
    (Region.byWireValue(state.regionId.toByte()) ?: Region.KANTO).name.lowercase()

/**
 * A plain trainer that is talked to (`trainerbattle_single` + one msgbox): the intro, the battle,
 * and once beaten only the post-battle line. The decomp's trainer flags are numbered after the flag
 * table, so the beaten state is kept as `<region>/OPENMMO_TRAINER_<id>`.
 */
suspend fun ScriptContext.gymTrainer(
    trainerId: Int,
    intro: DialogLine?,
    defeat: DialogLine?,
    postBattle: DialogLine,
) {
  val flag = "${regionKey()}/OPENMMO_TRAINER_$trainerId"
  if (isFlagSet(flag)) return say(postBattle)
  intro?.let { say(it) }
  if (trainerBattle(trainerId) != BattleResult.VICTORY) return
  setFlag(flag)
  defeat?.let { say(it) }
}

/** After a gym leader's flags are set: tell the player and refresh the badge case. */
fun ScriptContext.badgeAwarded(badge: Int) {
  val region = Region.byWireValue(state.regionId.toByte()) ?: Region.KANTO
  session.send(notice("$playerName received the ${BadgeNames.of(region, badge)}!"))
  val charId = state.characterId ?: return
  progress?.progress?.push(session, charId)
}
