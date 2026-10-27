package de.fiereu.openmmo.server.game.frontier

import de.fiereu.openmmo.server.game.script.vm.ScriptImage

/**
 * r5 merge: the Frontier Brains' own lines before a battle, read from the decomp's battle room
 * scripts (the msgbox of each facility's EventScript_Battle<Brain>Silver / <Brain>Gold) in the
 * assembled Emerald script image.
 */
object BrainTexts {
  private val SILVER =
      mapOf(
          Facility.TOWER to "BattleFrontier_BattleTowerBattleRoom_Text_LetMeSeeYourTalent",
          Facility.DOME to "BattleFrontier_BattleDomeBattleRoom_Text_LetsSeeYourStrategy",
          Facility.PALACE to "BattleFrontier_BattlePalaceBattleRoom_Text_ProveYourBondWithMons",
          Facility.ARENA to "BattleFrontier_BattleArenaBattleRoom_Text_IgniteMyPassionForBattle",
          Facility.FACTORY to
              "BattleFrontier_BattleFactoryBattleRoom_Text_ShakeOutKnowledgeBringItOn",
          Facility.PIKE to "BattleFrontier_BattlePikeRoomNormal_Text_HopeYouDidntUseUpLuck",
          Facility.PYRAMID to "BattleFrontier_BattlePyramidTop_Text_BringCourageToOurBattle",
      )
  private val GOLD =
      mapOf(
          Facility.TOWER to "BattleFrontier_BattleTowerBattleRoom_Text_LetsBeginShallWe",
          Facility.DOME to "BattleFrontier_BattleDomeBattleRoom_Text_UnleashAllPowerIPossess",
          Facility.PALACE to "BattleFrontier_BattlePalaceBattleRoom_Text_Kaaah",
          Facility.ARENA to
              "BattleFrontier_BattleArenaBattleRoom_Text_GretaLookingForwardToSeeingAgain",
          Facility.FACTORY to "BattleFrontier_BattleFactoryBattleRoom_Text_AllRightBringItOn",
          Facility.PIKE to "BattleFrontier_BattlePikeRoomNormal_Text_LucyYouAgain",
          Facility.PYRAMID to "BattleFrontier_BattlePyramidTop_Text_EverythingYouHave",
      )

  /** The brain's line, flattened to one line of plain text, or null without the image. */
  fun intro(f: Facility, gold: Boolean, playerName: String): String? {
    val label = (if (gold) GOLD else SILVER)[f] ?: return null
    val img = ScriptImage.of("hoenn") ?: return null
    val addr = img.labels[label] ?: return null
    val raw = img.texts[addr]?.content ?: return null
    return plain(raw, playerName)
  }

  internal fun plain(raw: String, playerName: String): String =
      raw.replace("{PLAYER}", playerName)
          .replace(Regex("""\{[A-Z_0-9 ]+\}"""), "")
          .replace(Regex("""\\[nlp]"""), " ")
          .removeSuffix("$")
          .replace(Regex("\\s+"), " ")
          .trim()
}
