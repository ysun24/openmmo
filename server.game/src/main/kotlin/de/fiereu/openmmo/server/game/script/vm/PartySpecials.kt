package de.fiereu.openmmo.server.game.script.vm

import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.net.game.packets.PokemonContainerPacket
import de.fiereu.openmmo.server.game.battle.BattleRng
import de.fiereu.openmmo.server.game.progression.DaycareRules
import de.fiereu.openmmo.server.game.progression.DaycareService
import de.fiereu.openmmo.server.game.script.ScriptContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The party / pokedex / day care / trade / PC specials (party_menu.c, daycare.c, trade.c,
 * pokedex.c, field_specials.c, script_pokemon_util.c). The party picker and the in-game trade scene
 * are browser UI over the 0xCE channel ("party", "trade").
 */
@Singleton
class PartySpecials @Inject constructor() : SpecialTable() {
  init {
    on("HealPlayerParty") { ctx ->
      ctx.healParty()
      0
    }
    // The party menu in its "choose a mon" modes: the slot (0-5) in VAR_0x8004, 0xFF for none.
    on("ChoosePartyMon", "ChooseMonForMoveTutor", "ChooseMonForMoveRelearner") { ctx ->
      ctx.setSpecialVar(4, pickParty(ctx, "Choose a POKéMON."))
      0
    }
    on("ChooseSendDaycareMon") { ctx ->
      ctx.setSpecialVar(4, pickParty(ctx, "Which POKéMON should we raise?"))
      0
    }
    on("SelectMoveDeleterMove", "MoveDeleterChooseMoveToForget") { ctx ->
      val mon = ctx.vmParty().getOrNull(ctx.specialVar(4))
      val moves = mon?.moves?.filter { it.id > 0 }.orEmpty()
      val names = moves.map { ctx.vmMoveName(it.id.toInt()) }
      val pick = if (names.isEmpty()) -1 else ctx.vmMenu("multichoice", names, 0, MENU_B, 1)
      ctx.setSpecialVar(5, if (pick == MENU_B || pick < 0) MAX_MON_MOVES else pick)
      0
    }
    on("MoveDeleterForgetMove") { ctx ->
      val slot = ctx.specialVar(4)
      val moveSlot = ctx.specialVar(5)
      val mon = ctx.vmParty().getOrNull(slot) ?: return@on 0
      val moves =
          mon.moves.filterIndexed { i, _ -> i != moveSlot } +
              mon.moves.getOrNull(moveSlot)?.copy(id = 0, pp = 0)
      update(ctx, mon.copy(moves = moves.filterNotNull().take(4)))
      0
    }
    on("BufferMoveDeleterNicknameAndMove") { ctx ->
      val mon = ctx.vmParty().getOrNull(ctx.specialVar(4))
      ctx.bufferString(1, ctx.vmMonName(ctx.specialVar(4)))
      ctx.bufferString(
          2, mon?.moves?.getOrNull(ctx.specialVar(5))?.let { ctx.vmMoveName(it.id.toInt()) } ?: "")
      0
    }
    on("GetNumMovesSelectedMonHas") { ctx ->
      ctx.vmParty().getOrNull(ctx.specialVar(4))?.moves?.count { it.id > 0 } ?: 0
    }
    on("TeachMoveRelearnerMove") { ctx ->
      // The relearner's move list is a browser menu of the level-up moves the mon lacks.
      ctx.setResult(0)
      0
    }
    on("BufferTMHMMoveName") { ctx ->
      ctx.bufferString(1, ctx.vmItemName(ctx.specialVar(4), 1))
      0
    }
    on("CapeBrinkGetMoveToTeachLeadPokemon") { ctx ->
      // FRLG Cape Brink: the ultimate move for a fully evolved starter in the lead.
      val lead = ctx.vmParty().firstOrNull { !it.isEgg } ?: return@on 0
      val move =
          when (lead.dexId) {
            3 -> FRENZY_PLANT
            6 -> BLAST_BURN
            9 -> HYDRO_CANNON
            else -> 0
          }
      if (move == 0) return@on 0
      ctx.setSpecialVar(5, move)
      ctx.bufferString(2, ctx.vmMoveName(move))
      1
    }
    on("HasLearnedAllMovesFromCapeBrinkTutor") { ctx -> 0 }
    on("SavePlayerParty", "LoadPlayerParty", "LoadPlayerBag", "ReducePlayerPartyToThree") { 0 }
    on("CountPartyNonEggMons", "CalculatePlayerPartyCount") { ctx ->
      val n = ctx.vmParty().count { !it.isEgg }
      if (n == 0) ctx.setResult(0)
      n
    }
    on("CountPartyAliveNonEggMons") { ctx -> ctx.vmParty().count { !it.isEgg && it.hp > 0 } }
    on("CountPartyAliveNonEggMons_IgnoreVar0x8004Slot") { ctx ->
      val skip = ctx.specialVar(4)
      ctx.vmParty().filterIndexed { i, m -> i != skip && !m.isEgg && m.hp > 0 }.size
    }
    on("ScriptGetPartyMonSpecies", "GetPartyMonSpecies") { ctx ->
      val m = ctx.vmParty().getOrNull(ctx.specialVar(4)) ?: return@on 0
      if (m.isEgg) SPECIES_EGG else internal(ctx, m.dexId)
    }
    on("IsSelectedMonEgg") { ctx ->
      ctx.setResult(if (ctx.vmParty().getOrNull(ctx.specialVar(4))?.isEgg == true) 1 else 0)
      0
    }
    on("IsBadEggInParty") { ctx -> 0 }
    on("BufferMonNickname") { ctx ->
      ctx.bufferString(1, ctx.vmMonName(ctx.specialVar(4)))
      0
    }
    on("GetSelectedMonNicknameAndSpecies") { ctx ->
      val m = ctx.vmParty().getOrNull(ctx.specialVar(4)) ?: return@on 0
      ctx.bufferString(1, ctx.vmMonName(ctx.specialVar(4)))
      internal(ctx, m.dexId)
    }
    on("ChangePokemonNickname", "ChangeBoxPokemonNickname") { ctx ->
      // The naming screen: a text prompt in the browser.
      val slot = ctx.specialVar(4)
      val mon = ctx.vmParty().getOrNull(slot) ?: return@on 0
      val entered =
          ctx.services?.ui?.askText(ctx.session, "name", ctx.vmMonName(slot), 10) ?: return@on 0
      if (entered.isNotBlank()) update(ctx, mon.copy(nickname = entered.uppercase()))
      0
    }
    on("NameRaterWasNicknameChanged") { ctx -> 1 }
    on("IsMonOTIDNotPlayers", "IsMonOTNameNotPlayers", "MonOTNameNotPlayer") { ctx ->
      val m = ctx.vmParty().getOrNull(ctx.specialVar(4))
      val r = if (m != null && m.ot != ctx.playerName) 1 else 0
      ctx.setResult(r)
      r
    }
    on("GetLeadMonFriendship", "GetLeadMonFriendshipScore") { ctx ->
      val f = ctx.vmParty().firstOrNull { !it.isEgg }?.friendship ?: 0
      when {
        f == 255 -> 6
        f >= 200 -> 5
        f >= 150 -> 4
        f >= 100 -> 3
        f >= 50 -> 2
        f >= 1 -> 1
        else -> 0
      }
    }
    on("IsStarterInParty") { ctx ->
      val starter = ctx.getVar("hoenn/VAR_STARTER_MON")
      val dex = listOf(252, 255, 258).getOrElse(starter) { 252 }
      if (ctx.vmParty().any { it.dexId == dex }) 1 else 0
    }
    on("GetStarterSpecies") { ctx ->
      internal(ctx, listOf(252, 255, 258).getOrElse(ctx.getVar("hoenn/VAR_STARTER_MON")) { 252 })
    }
    on("DoesPlayerPartyContainSpecies", "PlayerPartyContainsSpeciesWithPlayerID") { ctx ->
      val dex = ctx.vm?.dexOf(ctx.specialVar(4)) ?: 0
      val r = if (ctx.vmParty().any { it.dexId == dex && !it.isEgg }) 1 else 0
      ctx.setResult(r)
      r
    }
    on("IsLastMonThatKnowsSurf") { ctx ->
      val party = ctx.vmParty()
      val slot = ctx.specialVar(4)
      val others =
          party.filterIndexed { i, m -> i != slot && m.moves.any { it.id.toInt() == SURF } }
      ctx.setResult(if (others.isEmpty() && ctx.vmItemCount(0) == 0) 1 else 0)
      0
    }
    on("IsGrassTypeInParty") { ctx ->
      val species = ctx.services?.species
      ctx.setResult(
          if (ctx.vmParty().any { m ->
            species?.get(m.dexId)?.let { it.type1.name == "GRASS" || it.type2.name == "GRASS" } ==
                true
          })
              1
          else 0)
      0
    }
    on("IsPokerusInParty") { ctx ->
      ctx.setResult(0)
      0
    }
    on("Special_AreLeadMonEVsMaxedOut") { ctx ->
      val m = ctx.vmParty().firstOrNull { !it.isEgg }
      ctx.setResult(if (m != null && m.eVs.total >= 510) 1 else 0)
      0
    }
    on("IsLeadMonNicknamedOrNotEnglish") { ctx ->
      val m = ctx.vmParty().firstOrNull { !it.isEgg }
      if (m != null &&
          m.nickname.isNotBlank() &&
          !m.nickname.equals(ctx.services?.species?.get(m.dexId)?.name, true))
          1
      else 0
    }
    on("DoesPartyHaveEnigmaBerry") { ctx -> 0 }
    on("EggHatch") { ctx ->
      val slot = ctx.specialVar(4)
      val m = ctx.vmParty().getOrNull(slot) ?: return@on 0
      update(ctx, m.copy(isEgg = false, nickname = ""))
      ctx.bufferSpeciesName(1, m.dexId)
      ctx.ui("monpic") {
        put("dex", m.dexId)
        put("show", true)
        put("hatch", true)
      }
      0
    }
    // Pokedex: SetSeenMon (0x8004 = species), counts and the national dex.
    on("SetSeenMon") { ctx ->
      val dex = ctx.vm?.dexOf(ctx.specialVar(4)) ?: return@on 0
      ctx.state.characterId?.let { ctx.characters?.registerSeen(it, listOf(dex)) }
      0
    }
    on("GetPokedexCount", "ScriptGetPokedexInfo") { ctx ->
      val stored = ctx.state.characterId?.let { ctx.characters?.getCharacter(it) }
      val national = ctx.specialVar(4) != 0
      val seen = stored?.dexSeen.orEmpty().filter { national || it <= HOENN_DEX_LIMIT }
      val caught = stored?.dexCaught.orEmpty().filter { national || it <= HOENN_DEX_LIMIT }
      ctx.setSpecialVar(5, seen.size)
      ctx.setSpecialVar(6, caught.size)
      if (ctx.isFlagSet("${ctx.regionName}/FLAG_SYS_NATIONAL_DEX") ||
          ctx.isFlagSet("${ctx.regionName}/FLAG_SYS_NATIONAL_DEX_FRLG"))
          1
      else 0
    }
    on("IsNationalPokedexEnabled") { ctx ->
      if (ctx.getVar("${ctx.regionName}/VAR_NATIONAL_DEX") != 0 ||
          ctx.isFlagSet("${ctx.regionName}/FLAG_SYS_NATIONAL_DEX"))
          1
      else 0
    }
    on("EnableNationalPokedex") { ctx ->
      ctx.setFlag("${ctx.regionName}/FLAG_SYS_NATIONAL_DEX")
      ctx.setVar("${ctx.regionName}/VAR_NATIONAL_DEX", NATIONAL_DEX_MAGIC)
      0
    }
    on("SetUnlockedPokedexFlags") { ctx -> 0 }
    on("HasAllHoennMons", "HasAllMons", "HasAllKantoMons") { ctx ->
      val caught =
          ctx.state.characterId?.let { ctx.characters?.getCharacter(it) }?.dexCaught.orEmpty()
      ctx.setResult(if (caught.size >= KANTO_DEX_COUNT) 1 else 0)
      0
    }
    on("GetProfOaksRatingMessage", "ShowPokedexRatingMessage") { ctx ->
      val caught =
          ctx.state.characterId?.let { ctx.characters?.getCharacter(it) }?.dexCaught?.size ?: 0
      ctx.bufferString(1, caught.toString())
      0
    }

    // In-game trades: 0x8004 = INGAME_TRADE_*, 0x8005 = the player's party slot.
    on("GetInGameTradeSpeciesInfo") { ctx ->
      val t = trade(ctx) ?: return@on 0
      ctx.bufferSpeciesName(1, ctx.vm!!.dexOf(t.requested))
      ctx.bufferSpeciesName(2, ctx.vm!!.dexOf(t.species))
      t.requested
    }
    on("GetTradeSpecies") { ctx ->
      val m = ctx.vmParty().getOrNull(ctx.specialVar(5)) ?: return@on 0
      if (m.isEgg) 0 else internal(ctx, m.dexId)
    }
    on("CreateInGameTradePokemon") { ctx ->
      ctx.vm?.state?.pendingTrade = ctx.specialVar(4)
      0
    }
    on("DoInGameTradeScene") { ctx ->
      doTrade(ctx)
      0
    }

    // Day care (daycare.c over DaycareService).
    on("GetDaycareState") { ctx ->
      val stored = ctx.state.characterId?.let { ctx.characters?.getCharacter(it) } ?: return@on 0
      when (ctx.progress?.daycare?.state(stored)) {
        DaycareService.State.EGG_WAITING -> DAYCARE_EGG_WAITING
        DaycareService.State.ONE -> DAYCARE_ONE_MON
        DaycareService.State.TWO -> DAYCARE_TWO_MONS
        else -> DAYCARE_NO_MONS
      }
    }
    on("GetDaycareMonNicknames") { ctx ->
      val stored = ctx.state.characterId?.let { ctx.characters?.getCharacter(it) } ?: return@on 0
      val mons = ctx.progress?.daycare?.main(stored).orEmpty()
      mons.getOrNull(0)?.let { ctx.bufferString(1, monName(ctx, it)) }
      mons.getOrNull(1)?.let { ctx.bufferString(2, monName(ctx, it)) }
      0
    }
    on("StoreSelectedPokemonInDaycare", "PutMonInRoute5Daycare") { ctx ->
      val c = ctx.state.characterId ?: return@on 0
      val mon = ctx.vmParty().getOrNull(ctx.specialVar(4)) ?: return@on 0
      ctx.bufferString(1, monName(ctx, mon))
      ctx.progress
          ?.daycare
          ?.deposit(
              c, mon.id, route5 = ctx.vm?.isFrlg == true && it(ctx) == "PutMonInRoute5Daycare")
      pushParty(ctx)
      0
    }
    on("TakePokemonFromDaycare", "TakePokemonFromRoute5Daycare") { ctx ->
      val c = ctx.state.characterId ?: return@on 0
      val stored = ctx.characters?.getCharacter(c) ?: return@on 0
      val route5 =
          ctx.vm?.isFrlg == true &&
              ctx.progress?.daycare?.route5(stored) != null &&
              ctx.progress?.daycare?.main(stored).isNullOrEmpty()
      val idx = if (route5) 0 else ctx.specialVar(4)
      val mon = ctx.progress?.daycare?.mons(stored, route5)?.getOrNull(idx)
      ctx.progress?.daycare?.withdraw(c, idx, route5)
      pushParty(ctx)
      mon?.let { internal(ctx, it.dexId) } ?: 0
    }
    on("GetNumLevelsGainedFromDaycare", "GetNumLevelsGainedForRoute5DaycareMon") { ctx ->
      val stored = ctx.state.characterId?.let { ctx.characters?.getCharacter(it) } ?: return@on 0
      val mons =
          ctx.progress?.daycare?.main(stored).orEmpty().ifEmpty {
            listOfNotNull(ctx.progress?.daycare?.route5(stored))
          }
      val mon = mons.getOrNull(ctx.specialVar(4)) ?: mons.firstOrNull() ?: return@on 0
      val rate = ctx.services?.species?.get(mon.dexId)?.growthRate ?: return@on 0
      val n = DaycareRules.levelsGained(stored, mon, rate)
      ctx.bufferString(1, monName(ctx, mon))
      ctx.bufferString(2, n.toString())
      n
    }
    on("GetDaycareCost", "GetDaycareCostAndPrepareString", "GetCostToWithdrawRoute5DaycareMon") {
        ctx ->
      val stored = ctx.state.characterId?.let { ctx.characters?.getCharacter(it) } ?: return@on 0
      val mons =
          ctx.progress?.daycare?.main(stored).orEmpty().ifEmpty {
            listOfNotNull(ctx.progress?.daycare?.route5(stored))
          }
      val mon = mons.getOrNull(ctx.specialVar(4)) ?: mons.firstOrNull() ?: return@on 0
      val rate = ctx.services?.species?.get(mon.dexId)?.growthRate ?: return@on 0
      val fee = DaycareRules.fee(DaycareRules.levelsGained(stored, mon, rate))
      ctx.setSpecialVar(5, fee)
      ctx.bufferString(1, monName(ctx, mon))
      ctx.bufferString(2, fee.toString())
      fee
    }
    on("IsThereMonInRoute5Daycare") { ctx ->
      val stored = ctx.state.characterId?.let { ctx.characters?.getCharacter(it) } ?: return@on 0
      if (ctx.progress?.daycare?.route5(stored) != null) 1 else 0
    }
    on("ShowDaycareLevelMenu") { ctx ->
      val stored = ctx.state.characterId?.let { ctx.characters?.getCharacter(it) } ?: return@on 0
      val mons = ctx.progress?.daycare?.main(stored).orEmpty()
      val names = mons.map { "${monName(ctx, it)} Lv${it.level}" }
      val pick = ctx.vmMenu("multichoice", names + "CANCEL", 0, MENU_B, 1)
      ctx.setResult(if (pick >= mons.size) DAYCARE_EXITED_LEVEL_MENU else pick)
      0
    }
    on("GiveEggFromDaycare") { ctx ->
      val c = ctx.state.characterId ?: return@on 0
      ctx.progress?.daycare?.takeEgg(ctx.session, c)
      0
    }
    on("RejectEggFromDayCare") { ctx ->
      ctx.state.characterId?.let { ctx.progress?.daycare?.discardEgg(it) }
      0
    }
    on("SetDaycareCompatibilityString") { ctx ->
      val stored = ctx.state.characterId?.let { ctx.characters?.getCharacter(it) } ?: return@on 0
      val d = ctx.progress?.daycare ?: return@on 0
      val c = d.compatibility(d.main(stored))
      ctx.setStringVar4(
          when {
            c >= 70 -> "The two seem to get along\\nvery well.$"
            c >= 50 -> "The two seem to get along.$"
            c >= 20 -> "The two don't seem to like\\neach other much.$"
            else -> "The two prefer to play with other\\nPOKéMON than each other.$"
          })
      0
    }
    on("CheckDaycareMonReceivedMail") { ctx -> 0 }

    // PC and boxes.
    on("ScriptCheckFreePokemonStorageSpace", "IsThereRoomInAnyBoxForMorePokemon") { ctx ->
      ctx.setResult(1)
      1
    }
    on("GetPCBoxToSendMon") { ctx -> 0 }
    on("ShouldShowBoxWasFullMessage") { ctx -> 0 }
    on(
        "ShowPokemonStorageSystemPC",
        "PlayerPC",
        "BedroomPC",
        "AccessHallOfFamePC",
        "CreatePCMenu",
        "ScriptMenu_CreatePCMultichoice") { ctx ->
          // The PC is a browser panel (r3-items); the script only shows that it opened.
          ctx.ui("pc") { put("open", true) }
          ctx.setResult(0)
          0
        }

    // Size records (Seedot / Lotad / Magikarp / Heracross).
    on(
        "GetSeedotSizeRecordInfo",
        "GetLotadSizeRecordInfo",
        "GetMagikarpSizeRecordInfo",
        "GetHeracrossSizeRecordInfo") { ctx ->
          val rec = ctx.getVar("${ctx.regionName}/OPENMMO_SIZE_${it(ctx)}")
          ctx.bufferString(2, "%d.%d".format(rec / 10, rec % 10))
          0
        }
    on("CompareSeedotSize", "CompareLotadSize", "CompareMagikarpSize", "CompareHeracrossSize") { ctx
      ->
      val mon = ctx.vmParty().getOrNull(ctx.specialVar(4)) ?: return@on 0
      val key =
          "${ctx.regionName}/OPENMMO_SIZE_${it(ctx).replace("Compare", "Get").replace("Size", "SizeRecordInfo")}"
      val size = (mon.seed.toLong() and 0xFFFF).toInt() % 100 + 100
      ctx.bufferString(2, "%d.%d".format(size / 10, size % 10))
      val best = ctx.getVar(key)
      if (size > best) {
        ctx.setVar(key, size)
        3
      } else 2
    }
    on("BufferVarsForIVRater") { ctx ->
      val m = ctx.vmParty().getOrNull(ctx.specialVar(4)) ?: return@on 0
      val iv = m.iVs
      ctx.setSpecialVar(5, iv.total)
      ctx.setSpecialVar(6, 0)
      ctx.setSpecialVar(7, maxOf(iv.hp, iv.atk, iv.def, iv.spAtk, iv.spDef, iv.spd))
      0
    }
    on("ChooseStarter") { ctx ->
      ctx.setVar(
          "hoenn/VAR_STARTER_MON",
          listOf(252, 255, 258).indexOf(ctx.chooseHoennStarter()).coerceAtLeast(0))
      0
    }
    on("CheckRelicanthWailord") { ctx ->
      val party = ctx.vmParty()
      ctx.setResult(
          if (party.firstOrNull()?.dexId == 369 && party.lastOrNull()?.dexId == 321) 1 else 0)
      0
    }
    on("SampleResortGorgeousMonAndReward") { ctx -> 0 }
    on("DaisyMassageServices") { ctx ->
      val m = ctx.vmParty().getOrNull(ctx.specialVar(4)) ?: return@on 0
      update(ctx, m.copy(friendship = minOf(255, m.friendship + 10)))
      0
    }
    // UpdatePickStateFromSpecialVar8005 is r5-minigames' (FameCheckerSpecials).
    on("CreateEnemyEventMon") { ctx ->
      // FRLG: the mon a seteventmon / special battle fights is gEnemyParty[0] (0x8004 species).
      val vm = ctx.vm ?: return@on 0
      vm.state.wild = Triple(vm.dexOf(ctx.specialVar(4)), ctx.specialVar(5), ctx.specialVar(6))
      0
    }
  }

  private fun it(ctx: ScriptContext): String = ctx.vm?.currentSpecial ?: ""

  private fun internal(ctx: ScriptContext, dex: Int): Int = ctx.vm?.internalOf(dex) ?: dex

  private fun monName(ctx: ScriptContext, m: Pokemon): String =
      m.nickname.ifBlank { ctx.services?.species?.get(m.dexId)?.name?.uppercase() ?: "" }

  private fun update(ctx: ScriptContext, mon: Pokemon) {
    val c = ctx.state.characterId ?: return
    ctx.characters?.updatePokemon(c, mon)
    pushParty(ctx)
  }

  private fun pushParty(ctx: ScriptContext) {
    ctx.session.send(
        PokemonContainerPacket(
            container = PokemonContainer.PARTY,
            hasChange = true,
            delete = false,
            pokemon = ctx.vmParty()))
  }

  /** The party picker: the chosen slot, or 0xFF (PARTY_NOTHING_CHOSEN). */
  private suspend fun pickParty(ctx: ScriptContext, prompt: String): Int {
    val party = ctx.vmParty()
    if (party.isEmpty()) return NOTHING_CHOSEN
    val pick =
        ctx.ask("party", -1) {
          put("prompt", prompt)
          put(
              "mons",
              JsonArray(
                  party.mapIndexed { i, m ->
                    buildJsonObject {
                      put("slot", i)
                      put("dex", m.dexId)
                      put("name", monName(ctx, m))
                      put("level", m.level.toInt())
                      put("egg", m.isEgg)
                      put("hp", m.hp.toInt())
                    }
                  }))
        }
    return if (pick in party.indices) pick else NOTHING_CHOSEN
  }

  private data class Trade(
      val nickname: String,
      val ot: String,
      val species: Int,
      val requested: Int,
      val item: Int,
      val otId: Long,
      val personality: Long,
      val ivs: List<Int>,
  )

  private fun trade(ctx: ScriptContext, id: Int = ctx.specialVar(4)): Trade? {
    val row = ctx.vm?.img?.stringTables?.get("sIngameTrades")?.get(id) ?: return null
    fun n(i: Int) = row.getOrNull(i)?.toLongOrNull() ?: 0L
    return Trade(
        row[0],
        row[1],
        n(2).toInt(),
        n(3).toInt(),
        n(4).toInt(),
        n(5),
        n(6),
        row.drop(7).map { it.toIntOrNull() ?: 0 })
  }

  /** DoInGameTradeScene: the trade animation in the browser, then the mons change hands. */
  private suspend fun doTrade(ctx: ScriptContext) {
    val vm = ctx.vm ?: return
    val c = ctx.state.characterId ?: return
    val id = vm.state.pendingTrade ?: ctx.specialVar(4)
    val t = trade(ctx, id) ?: return
    val slot = ctx.specialVar(5)
    val given = ctx.vmParty().getOrNull(slot) ?: return
    val dex = vm.dexOf(t.species)
    val factory = ctx.services?.monFactory ?: return
    val rolled = factory.create(dex, given.level.toInt(), BattleRng()) ?: return
    val heldWire = ctx.vmItemDef(t.item)?.let { ctx.services?.items?.idOrNull(it) } ?: 0
    val ivs = t.ivs
    val received =
        rolled.copy(
            ownerId = c,
            container = PokemonContainer.PARTY,
            containerSlot = given.containerSlot,
            nickname = t.nickname,
            ot = t.ot,
            seed = t.personality.toInt(),
            heldItem = heldWire,
            iVs =
                if (ivs.size == 6)
                    de.fiereu.openmmo.common.enums.IVs().apply {
                      hp = ivs[0].coerceIn(0, 31)
                      atk = ivs[1].coerceIn(0, 31)
                      def = ivs[2].coerceIn(0, 31)
                      spd = ivs[3].coerceIn(0, 31)
                      spAtk = ivs[4].coerceIn(0, 31)
                      spDef = ivs[5].coerceIn(0, 31)
                    }
                else rolled.iVs)
    ctx.ask("trade", 0) {
      put("giveDex", given.dexId)
      put("giveName", monName(ctx, given))
      put("getDex", dex)
      put("getName", t.nickname)
      put("ot", t.ot)
    }
    val store = ctx.characters ?: return
    val ok =
        store.commitDurably(c) { s ->
          val party = s.pokemon.map { if (it.id == given.id) received else it }
          s.copy(pokemon = party.toMutableList()).withOwnedInDex()
        }
    if (ok) {
      vm.state.pendingTrade = null
      pushParty(ctx)
    }
  }

  private companion object {
    const val NOTHING_CHOSEN = 0xFF
    const val MENU_B = 127
    const val MAX_MON_MOVES = 4
    const val SPECIES_EGG = 412
    const val SURF = 57
    const val FRENZY_PLANT = 338
    const val BLAST_BURN = 307
    const val HYDRO_CANNON = 308
    const val HOENN_DEX_LIMIT = 386
    const val KANTO_DEX_COUNT = 150
    const val NATIONAL_DEX_MAGIC = 0x0302
    const val DAYCARE_NO_MONS = 0
    const val DAYCARE_EGG_WAITING = 1
    const val DAYCARE_ONE_MON = 2
    const val DAYCARE_TWO_MONS = 3
    const val DAYCARE_EXITED_LEVEL_MENU = 2
  }
}
