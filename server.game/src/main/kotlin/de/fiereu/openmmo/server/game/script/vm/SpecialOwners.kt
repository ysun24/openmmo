package de.fiereu.openmmo.server.game.script.vm

/**
 * Which round-5 lane owns a decomp special (R5 brief "Lanes" table): contests (contests,
 * Pokeblocks, the Berry Blender, berry trees, the museum, ribbons and contest conditions),
 * the Battle Frontier lane (frontier facilities, Trainer Tower / Hill, tents, battle points) and
 * minigames (slots, roulette, coins, Berry Crush, Dodrio, Pokemon Jump, the Fame Checker, the
 * Union Room, record mixing, Mystery Gift, easy chat, trendy phrases, interviews). Everything
 * else is r5-scripts', implemented by the handlers of [StoryScriptModule].
 */
object SpecialOwners {
  private val contests =
      Regex(
          "(?i)contest|pokeblock|blender|museum|painting|berrytree|BerryTree|Ribbon|" +
              "^CheckLeadMon(Cool|Beauty|Cute|Smart|Tough)|IncrementDaily(Planted|Picked)Berries|" +
              "ObjectEventInteraction(Get|Plant|Pick|Water|Remove)Berry|DoWateringBerryTreeAnim|" +
              "^Bag_ChooseBerry|PlayerHasBerries|HasAtLeastOneBerry|DoBerryBlending|" +
              "GetDewfordHallPaintingNameIndex")
  private val frontier =
      Regex(
          "(?i)frontier|battletower|BattleTower|^Dome|dome|^Palace|^Arena|^Factory|^Pike|pike|" +
              "pyramid|trainertower|TrainerTower|trainerhill|TrainerHill|BattleTent|Tent|" +
              "BattlePoints|RankingHall|EReader|Call(Battle(Arena|Factory|Palace)|Apprentice)|SaveForBattleTowerLink|ChooseHalfPartyForBattle|" +
              "ReducePlayerPartyToSelectedMons|BufferBattleTowerElevatorFloors|" +
              "PlayerNotAtTrainerHillEntrance|IsPlayerNotInTrainerTowerLobby|" +
              "ShowTrainerHillRecords|ShowBattleRecords|SetBattleTowerLinkPlayerGfx")
  private val minigames =
      Regex(
          "(?i)^Slot|slotmachine|SlotMachine|roulette|Coin|BerryCrush|BerryPowder|Dodrio|" +
              "PokemonJump|FameChecker|UnionRoom|RecordMix|MysteryGift|WonderCard|WonderNews|" +
              "EasyChat|Trendy|Interview|PlaySlotMachine|ChooseMonForWirelessMinigame|" +
              "IsPokemonJumpSpeciesInParty|IsDodrioInParty|ValidateMixingGameLanguage|" +
              "ShowWirelessCommunicationScreen|BerryPowderVendor|GetMysteryGiftCardStat|" +
              "RetrieveWonderNewsVal|ValidateSavedWonderCard|ShouldDistributeEonTicket")

  fun lane(special: String): String? =
      when {
        contests.containsMatchIn(special) -> "r5-contests"
        frontier.containsMatchIn(special) -> "r5-frontier"
        minigames.containsMatchIn(special) -> "r5-minigames"
        else -> null
      }

  fun otherLane(special: String): Boolean = lane(special) != null
}
