package de.fiereu.openmmo.server.game.frontier

import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import de.fiereu.openmmo.common.CharacterPermissions
import de.fiereu.openmmo.server.game.script.ScriptContext
import de.fiereu.openmmo.server.game.script.ScriptSpecial
import de.fiereu.openmmo.server.game.services.command.ChatCommand
import de.fiereu.openmmo.server.game.services.command.CommandContext
import javax.inject.Inject
import javax.inject.Singleton

/** The attendants that open a facility desk, by their decomp object event script. */
object FrontierDesks {
  val BY_SCRIPT: Map<String, String> =
      mapOf(
          "BattleFrontier_BattleTowerLobby_EventScript_SinglesAttendant" to "tower:singles",
          "BattleFrontier_BattleTowerLobby_EventScript_DoublesAttendant" to "tower:doubles",
          "BattleFrontier_BattleTowerLobby_EventScript_MultisAttendant" to "tower:multis",
          "BattleFrontier_BattleTowerLobby_EventScript_LinkMultisAttendant" to "tower:linkmultis",
          "BattleFrontier_BattleDomeLobby_EventScript_SinglesAttendant" to "dome:singles",
          "BattleFrontier_BattleDomeLobby_EventScript_DoublesAttendant" to "dome:doubles",
          "BattleFrontier_BattlePalaceLobby_EventScript_SinglesAttendant" to "palace:singles",
          "BattleFrontier_BattlePalaceLobby_EventScript_DoublesAttendant" to "palace:doubles",
          "BattleFrontier_BattleArenaLobby_EventScript_Attendant" to "arena",
          "BattleFrontier_BattleFactoryLobby_EventScript_SinglesAttendant" to "factory:singles",
          "BattleFrontier_BattleFactoryLobby_EventScript_DoublesAttendant" to "factory:doubles",
          "BattleFrontier_BattlePikeLobby_EventScript_Attendant" to "pike",
          "BattleFrontier_BattlePyramidLobby_EventScript_Attendant" to "pyramid",
          "BattleFrontier_ExchangeServiceCorner_EventScript_DecorClerk1" to "exchange:Decor1",
          "BattleFrontier_ExchangeServiceCorner_EventScript_DecorClerk2" to "exchange:Decor2",
          "BattleFrontier_ExchangeServiceCorner_EventScript_VitaminClerk" to "exchange:Vitamins",
          "BattleFrontier_ExchangeServiceCorner_EventScript_HoldItemClerk" to "exchange:HoldItems",
          "SlateportCity_BattleTentLobby_EventScript_Attendant" to "tent:slateport",
          "VerdanturfTown_BattleTentLobby_EventScript_Attendant" to "tent:verdanturf",
          "FallarborTown_BattleTentLobby_EventScript_Attendant" to "tent:fallarbor",
          "TrainerHill_Entrance_EventScript_Attendant" to "hill",
          "TrainerTower_Lobby_EventScript_Receptionist" to "ttower",
      )

  val ALL: Set<String> = BY_SCRIPT.values.toSet() + "card"
}

/**
 * `/frontier <desk>` opens a facility desk anywhere (developer), e.g. `/frontier tower:singles`.
 */
class FrontierCommand @Inject constructor(private val frontier: FrontierService) : ChatCommand {
  override val name = "frontier"
  override val usage = "/frontier <desk> | /frontier bp <n>"
  override val description = "opens a battle facility desk (developer)"
  override val permission = CharacterPermissions.DEVELOPER

  override suspend fun run(ctx: CommandContext) {
    val desk =
        ctx.args.getOrNull(0)
            ?: return ctx.reply("Desks: ${FrontierDesks.ALL.sorted().joinToString()}")
    if (desk == "bp") {
      val n = ctx.args.getOrNull(1)?.toIntOrNull() ?: return ctx.reply("Usage: /frontier bp <n>")
      frontier.save(ctx.characterId).addPoints(n)
      frontier.persist(ctx.characterId)
      return ctx.reply("Added $n BP.")
    }
    if (desk == "streak") {
      // /frontier streak <key> <n>: set a streak (to reach a Frontier Brain in testing).
      val key = ctx.args.getOrNull(1) ?: return ctx.reply("Usage: /frontier streak <key> <n>")
      val n = ctx.args.getOrNull(2)?.toIntOrNull() ?: 0
      frontier.save(ctx.characterId).streak(key).apply {
        current = n
        active = true
      }
      frontier.persist(ctx.characterId)
      return ctx.reply("Streak $key = $n.")
    }
    if (desk !in FrontierDesks.ALL) return ctx.reply("Unknown desk $desk.")
    frontier.open(ctx.session, desk)
  }
}

/**
 * The frontier specials (the decomp names of the r5-frontier lane). The facility functions read
 * VAR_0x8004 (the sub-function) and VAR_0x8005/6 like the decomp; the ones that open a facility
 * screen open the matching desk instead of the decomp's own screen.
 */
@Singleton
class FrontierSpecials @Inject constructor(private val frontier: FrontierService) : ScriptSpecial {
  override val names: Set<String> =
      setOf(
          "CallBattleTowerFunc",
          "CallBattleDomeFunction",
          "CallBattlePalaceFunction",
          "CallBattleArenaFunction",
          "CallBattleFactoryFunction",
          "CallBattlePikeFunction",
          "CallBattlePyramidFunction",
          "CallFrontierUtilFunc",
          "CallTrainerHillFunction",
          "CallTrainerTowerFunc",
          "CallVerdanturfTentFunction",
          "CallFallarborTentFunction",
          "CallSlateportTentFunction",
          "GetFrontierBattlePoints",
          "GiveFrontierBattlePoints",
          "TakeFrontierBattlePoints",
          "ShowBattlePointsWindow",
          "CloseBattlePointsWindow",
          "UpdateBattlePointsWindow",
          "CheckPartyBattleTowerBanlist",
          "ChoosePartyForBattleFrontier",
          "GetBattleTowerSinglesStreak",
          "AwardBattleTowerRibbons",
          "BattlePyramidChooseMonHeldItems",
          "DoBattlePyramidMonsHaveHeldItem",
          "GetBattlePyramidHint",
          "TryStoreHeldItemsInPyramidBag",
          "ChooseItemsToTossFromPyramidBag",
          "ShowTrainerHillRecords",
          "PlayerNotAtTrainerHillEntrance",
          "IsPlayerNotInTrainerTowerLobby",
          "ReadTrainerTowerAndValidate",
          "ShowFrontierExchangeCornerItemIconWindow",
          "CloseFrontierExchangeCornerItemIconWindow",
          "ShowRankingHallRecordsWindow",
          "ScrollRankingHallRecordsWindow",
          "CloseBattlePikeCurtain",
          "DoDomeConfetti",
          "BufferBattleTowerElevatorFloors",
          "SetBattleTowerProperty",
          "SetBattleTowerParty",
          "SaveBattleTowerProgress",
          "BattleTower_SoftReset",
          "ChooseNextBattleTowerTrainer",
          "PrintBattleTowerTrainerGreeting",
          "GiveBattleTowerPrize",
          "DetermineBattleTowerPrize",
          "BattleTowerUtil",
          "BattleTowerMapScript2",
          "ChooseBattleTowerPlayerParty",
          "TrySetBattleTowerLinkType",
          "SaveForBattleTowerLink",
          "LinkRetireStatusWithBattleTowerPartner",
          "BattleTowerReconnectLink",
          "SetBattleTowerLinkPlayerGfx",
          "TryHideBattleTowerReporter",
          "TryInitBattleTowerAwardManObjectEvent",
          "ShowFrontierGamblerGoMessage",
          "ShowFrontierGamblerLookingMessage",
          "ShowFrontierManiacMessage",
      )

  override suspend fun run(ctx: ScriptContext, name: String): Int {
    val charId = ctx.state.characterId ?: return 0
    val save = frontier.save(charId)
    val f = ctx.specialVar(4)
    return when (name) {
      "GetFrontierBattlePoints" -> save.battlePoints
      "GiveFrontierBattlePoints" -> {
        save.addPoints(ctx.specialVar(4))
        frontier.persist(charId)
        0
      }
      "TakeFrontierBattlePoints" -> {
        save.battlePoints = (save.battlePoints - ctx.specialVar(4)).coerceAtLeast(0)
        frontier.persist(charId)
        0
      }
      "GetBattleTowerSinglesStreak" ->
          maxOf(save.streak("tower:singles:50").current, save.streak("tower:singles:open").current)
      "CheckPartyBattleTowerBanlist" -> {
        val party =
            frontier.party(charId).map {
              FrontierRules.EntrantCheck(it.dexId, it.level.toInt(), it.heldItem, it.isEgg)
            }
        if (FrontierRules.eligibleSlots(party, LevelMode.OPEN, frontier.data.bannedSpecies).size <
            3)
            1
        else 0
      }
      // CallFrontierUtilFunc: FRONTIER_UTIL_FUNC_GET_FACILITY_SYMBOL_COUNT (11) reads the symbols
      // of
      // VAR_FRONTIER_FACILITY; GIVE_BATTLE_POINTS (10) etc. are run by the desks themselves.
      "CallFrontierUtilFunc" ->
          when (f) {
            11 ->
                save.symbolsOf(
                    Facility.entries.getOrElse(ctx.varGet("VAR_FRONTIER_FACILITY")) {
                      Facility.TOWER
                    })
            else -> 0
          }
      "CallBattleTowerFunc" -> {
        openFor(ctx, "tower:singles", f)
        0
      }
      "CallBattleDomeFunction" -> {
        openFor(ctx, "dome:singles", f)
        0
      }
      "CallBattlePalaceFunction" -> {
        openFor(ctx, "palace:singles", f)
        0
      }
      "CallBattleArenaFunction" -> {
        openFor(ctx, "arena", f)
        0
      }
      "CallBattleFactoryFunction" -> {
        openFor(ctx, "factory:singles", f)
        0
      }
      "CallBattlePikeFunction" -> {
        openFor(ctx, "pike", f)
        0
      }
      "CallBattlePyramidFunction" -> {
        openFor(ctx, "pyramid", f)
        0
      }
      "CallTrainerHillFunction" -> {
        openFor(ctx, "hill", f)
        0
      }
      "CallTrainerTowerFunc" -> {
        openFor(ctx, "ttower", f)
        0
      }
      "CallVerdanturfTentFunction" -> {
        openFor(ctx, "tent:verdanturf", f)
        0
      }
      "CallFallarborTentFunction" -> {
        openFor(ctx, "tent:fallarbor", f)
        0
      }
      "CallSlateportTentFunction" -> {
        openFor(ctx, "tent:slateport", f)
        0
      }
      "ShowBattlePointsWindow",
      "ShowRankingHallRecordsWindow",
      "ShowTrainerHillRecords" -> {
        frontier.open(ctx.session, "card")
        0
      }
      "PlayerNotAtTrainerHillEntrance",
      "IsPlayerNotInTrainerTowerLobby" -> 0
      "ReadTrainerTowerAndValidate" -> 1
      else -> 0
    }
  }

  /** Sub-function 0 of every facility function is its INIT: that is when the desk opens. */
  private fun openFor(ctx: ScriptContext, desk: String, sub: Int) {
    if (sub == 0) frontier.open(ctx.session, desk)
  }
}

/** r5-frontier's Dagger bindings (one line in the component's module list). */
@Module
interface FrontierModule {
  @Binds @IntoSet fun frontierSpecials(h: FrontierSpecials): ScriptSpecial

  @Binds @IntoSet fun frontierMiscSpecials(h: FrontierMiscSpecials): ScriptSpecial

  @Binds @IntoSet fun frontierCommand(c: FrontierCommand): ChatCommand

  @Binds fun frontierRepository(impl: JooqFrontierRepository): FrontierRepository
}
