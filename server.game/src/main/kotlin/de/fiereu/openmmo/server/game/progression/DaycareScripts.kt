package de.fiereu.openmmo.server.game.progression

import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.dialog.DialogLine
import de.fiereu.openmmo.dialog.generated.hoenn.Route117
import de.fiereu.openmmo.dialog.generated.hoenn.Route117_PokemonDayCare
import de.fiereu.openmmo.dialog.generated.kanto.DayCare
import de.fiereu.openmmo.dialog.generated.kanto.Route5_PokemonDayCare
import de.fiereu.openmmo.server.game.script.ScriptContext

/** The day care lady's and man's lines; Emerald and FireRed name a few of them differently. */
internal class DaycareTexts(
    val wouldYouLikeUsToRaise: DialogLine,
    val whichMonShouldWeRaise: DialogLine,
    val wellRaiseYourMon: DialogLine,
    val weCanRaiseOneMore: DialogLine,
    val husbandWasLookingForYou: DialogLine,
    val fineThenComeAgain: DialogLine,
    val notEnoughMoney: DialogLine,
    val takeOtherOneBackToo: DialogLine,
    val comeAgain: DialogLine,
    val goodToSeeYou: DialogLine,
    val yourMonHasGrownXLevels: DialogLine,
    val teamIsFull: DialogLine,
    val itWillCostX: DialogLine,
    val heresYourMon: DialogLine,
    val tookBackMon: DialogLine,
    val youHaveJustOneMon: DialogLine,
    val takeYourMonBack: DialogLine,
    val whatWillYouBattleWith: DialogLine,
    val youllBeLeftWithJustOne: DialogLine?,
    // The man outside.
    val seeWife: DialogLine,
    val doYouWantEgg: DialogLine,
    val iWillKeepDoYouWantIt: DialogLine,
    val illKeepIt: DialogLine,
    val noRoomForEgg: DialogLine,
    val receivedEgg: DialogLine,
    val takeGoodCareOfIt: DialogLine,
    val yourMonIsDoingFine: DialogLine,
    val yourMonsAreDoingFine: DialogLine,
)

internal val HOENN_DAYCARE =
    DaycareTexts(
        Route117_PokemonDayCare.WouldYouLikeUsToRaiseAMon,
        Route117_PokemonDayCare.WhichMonShouldWeRaise,
        Route117_PokemonDayCare.WellRaiseYourMon,
        Route117_PokemonDayCare.WeCanRaiseOneMore,
        Route117_PokemonDayCare.HusbandWasLookingForYou,
        Route117_PokemonDayCare.FineThenComeAgain,
        Route117_PokemonDayCare.NotEnoughMoney,
        Route117_PokemonDayCare.TakeOtherOneBackToo,
        Route117_PokemonDayCare.ComeAgain,
        Route117_PokemonDayCare.GoodToSeeYou,
        Route117_PokemonDayCare.YourMonHasGrownXLevels,
        Route117_PokemonDayCare.YourTeamIsFull,
        Route117_PokemonDayCare.ItWillCostX,
        Route117_PokemonDayCare.HeresYourMon,
        Route117_PokemonDayCare.TookBackMon,
        Route117_PokemonDayCare.YouHaveJustOneMon,
        Route117_PokemonDayCare.TakeYourMonBack,
        Route117_PokemonDayCare.WhatWillYouBattleWith,
        Route117_PokemonDayCare.YoullBeLeftWithJustOne,
        Route117.SeeWifeIfYoudLikeMeToRaiseMon,
        Route117.DoYouWantEgg,
        Route117.IWillKeepDoYouWantIt,
        Route117.IllKeepIt,
        Route117.YouHaveNoRoomForIt,
        Route117.ReceivedEgg,
        Route117.TakeGoodCareOfIt,
        Route117.YourMonIsDoingFine,
        Route117.YourMonsAreDoingFine,
    )

internal val KANTO_DAYCARE =
    DaycareTexts(
        DayCare.WouldYouLikeUsToRaiseMon,
        DayCare.WhichMonShouldWeRaise,
        DayCare.WellRaiseYourMon,
        DayCare.WeCanRaiseOneMore,
        DayCare.HusbandWasLookingForYou,
        DayCare.FineThenComeAgain,
        DayCare.NotEnoughMoney,
        DayCare.TakeOtherOneBackToo,
        DayCare.ComeAgain,
        DayCare.GoodToSeeYou,
        DayCare.YourMonHasGrownXLevels,
        DayCare.YourPartyIsFull,
        DayCare.ItWillCostX,
        DayCare.HeresYourMon,
        DayCare.TookBackMon,
        DayCare.YouHaveJustOneMon,
        DayCare.TakeYourMonBack,
        DayCare.WhatWillYouBattleWith,
        null,
        DayCare.ImDaycareManSpeakToMyWife,
        DayCare.DoYouWantEgg,
        DayCare.IWillKeepDoYouWantIt,
        DayCare.IllKeepIt,
        DayCare.YouHaveNoRoomForIt,
        DayCare.ReceivedEgg,
        DayCare.TakeGoodCareOfIt,
        DayCare.YourMonIsDoingFine,
        DayCare.YourMonsAreDoingFine,
    )

/**
 * The decomp's data/scripts/day_care.inc, for both regions: the lady inside takes and returns
 * monsters, the man outside hands over eggs. The party and level menus are pickers the browser
 * shows while the script waits ([ScriptProgress.pick]).
 */
internal object DaycareScripts {
  private const val PARTY_SIZE = 6
  private const val PICK_PARTY = 1
  private const val PICK_DAYCARE = 2

  private fun ScriptContext.p(): ScriptProgress = checkNotNull(progress) { "No progress service" }

  private fun ScriptContext.charId(): Long = checkNotNull(state.characterId)

  private fun ScriptContext.stored() = checkNotNull(p().characters.getCharacter(charId()))

  private fun ScriptContext.nameOf(mon: Pokemon): String =
      mon.nickname.ifEmpty { p().daycare.speciesName(mon.dexId) }

  /** GetDaycareMonNicknames: STR_VAR_1 and STR_VAR_2. */
  private fun ScriptContext.bufferDaycareNames() {
    val mons = p().daycare.main(stored())
    mons.getOrNull(0)?.let { bufferString(1, nameOf(it)) }
    mons.getOrNull(1)?.let { bufferString(2, nameOf(it)) }
  }

  suspend fun woman(ctx: ScriptContext, t: DaycareTexts) {
    when (ctx.p().state(ctx.charId())) {
      DaycareService.State.EGG_WAITING -> ctx.say(t.husbandWasLookingForYou)
      DaycareService.State.ONE -> {
        ctx.say(t.goodToSeeYou)
        displayLevelsGained(ctx, t, 0)
        if (ctx.askYesNo(t.weCanRaiseOneMore)) return giveMonToRaise(ctx, t)
        if (ctx.askYesNo(t.takeYourMonBack)) return tryRetrieve(ctx, t)
        ctx.say(t.comeAgain)
      }
      DaycareService.State.TWO -> {
        ctx.say(t.goodToSeeYou)
        displayLevelsGained(ctx, t, 0)
        displayLevelsGained(ctx, t, 1)
        if (ctx.askYesNo(t.takeYourMonBack)) return tryRetrieve(ctx, t)
        ctx.say(t.comeAgain)
      }
      DaycareService.State.EMPTY -> {
        if (ctx.askYesNo(t.wouldYouLikeUsToRaise)) return giveMonToRaise(ctx, t)
        ctx.say(t.fineThenComeAgain)
      }
    }
  }

  private suspend fun displayLevelsGained(ctx: ScriptContext, t: DaycareTexts, slot: Int) {
    val stored = ctx.stored()
    val mon = ctx.p().daycare.main(stored).getOrNull(slot) ?: return
    val gained = ctx.p().daycare.levelsGained(stored, mon)
    if (gained == 0) return
    ctx.bufferString(1, ctx.nameOf(mon))
    ctx.bufferString(2, gained.toString())
    ctx.say(t.yourMonHasGrownXLevels)
  }

  private suspend fun giveMonToRaise(ctx: ScriptContext, t: DaycareTexts) {
    val party = ctx.stored().pokemon
    if (party.count { !it.isEgg } == 1) return ctx.say(t.youHaveJustOneMon)
    // Emerald's CountPartyAliveNonEggMons counts the boxes too: with two monsters in all, one
    // would be left.
    val alive = ctx.stored().pcStorage.count { !it.isEgg } + party.count { !it.isEgg && it.hp > 0 }
    if (t.youllBeLeftWithJustOne != null && alive == 2) return ctx.say(t.youllBeLeftWithJustOne)
    ctx.say(t.whichMonShouldWeRaise)
    val monId = ctx.p().pick(ctx.session, ctx.charId(), PICK_PARTY) ?: return ctx.say(t.comeAgain)
    val mon = ctx.stored().pokemon.firstOrNull { it.id == monId } ?: return ctx.say(t.comeAgain)
    if (ctx.stored().pokemon.none { it.id != monId && !it.isEgg && it.hp > 0 }) {
      return ctx.say(t.whatWillYouBattleWith)
    }
    val refused = ctx.p().daycare.deposit(ctx.charId(), monId)
    if (refused != null) {
      ctx.p().tell(ctx.session, refused)
      return
    }
    ctx.p().progress.pushParty(ctx.session, ctx.charId())
    ctx.p().progress.push(ctx.session, ctx.charId())
    ctx.bufferString(1, ctx.nameOf(mon))
    ctx.say(t.wellRaiseYourMon)
    if (ctx.p().state(ctx.charId()) == DaycareService.State.ONE) {
      if (ctx.askYesNo(t.weCanRaiseOneMore)) return giveMonToRaise(ctx, t)
      ctx.say(t.comeAgain)
    }
  }

  private suspend fun tryRetrieve(ctx: ScriptContext, t: DaycareTexts) {
    if (ctx.stored().pokemon.size >= PARTY_SIZE) return ctx.say(t.teamIsFull)
    val mons = ctx.p().daycare.main(ctx.stored())
    val index =
        if (mons.size == 1) 0
        else ctx.p().pick(ctx.session, ctx.charId(), PICK_DAYCARE)?.toInt() ?: return ctx.say(t.comeAgain)
    val mon = mons.getOrNull(index) ?: return ctx.say(t.comeAgain)
    val stored = ctx.stored()
    val fee = DaycareRules.fee(ctx.p().daycare.levelsGained(stored, mon))
    ctx.bufferString(1, ctx.nameOf(mon))
    ctx.bufferString(2, fee.toString())
    if (!ctx.askYesNo(t.itWillCostX)) return ctx.say(t.comeAgain)
    if (stored.info.money < fee) return ctx.say(t.notEnoughMoney)
    val (refused, _) = ctx.p().daycare.withdraw(ctx.charId(), index)
    if (refused != null) {
      ctx.p().tell(ctx.session, refused)
      return
    }
    ctx.p().progress.pushParty(ctx.session, ctx.charId())
    ctx.p().progress.push(ctx.session, ctx.charId())
    ctx.p().daycare.sendMoney(ctx.session, ctx.charId())
    ctx.say(t.heresYourMon)
    ctx.say(t.tookBackMon)
    if (ctx.p().state(ctx.charId()) == DaycareService.State.ONE) {
      if (ctx.askYesNo(t.takeOtherOneBackToo)) return tryRetrieve(ctx, t)
    }
    ctx.say(t.comeAgain)
  }

  suspend fun man(ctx: ScriptContext, t: DaycareTexts) {
    ctx.bufferDaycareNames()
    when (ctx.p().state(ctx.charId())) {
      DaycareService.State.EGG_WAITING -> {
        if (ctx.askYesNo(t.doYouWantEgg) || ctx.askYesNo(t.iWillKeepDoYouWantIt)) {
          if (ctx.stored().pokemon.size >= PARTY_SIZE) return ctx.say(t.noRoomForEgg)
          val refused = ctx.p().daycare.takeEgg(ctx.session, ctx.charId())
          if (refused != null) {
            ctx.p().tell(ctx.session, refused)
            return
          }
          ctx.p().progress.push(ctx.session, ctx.charId())
          ctx.say(t.receivedEgg)
          ctx.say(t.takeGoodCareOfIt)
        } else {
          ctx.say(t.illKeepIt)
          ctx.p().daycare.discardEgg(ctx.charId())
        }
      }
      DaycareService.State.ONE -> ctx.say(t.yourMonIsDoingFine)
      DaycareService.State.TWO -> {
        ctx.say(t.yourMonsAreDoingFine)
        // SetDaycareCompatibilityString: one of four lines by score.
        ctx.p().tell(ctx.session, compatibilityText(ctx.p().daycare.compatibility(ctx.p().daycare.main(ctx.stored()))))
      }
      DaycareService.State.EMPTY -> ctx.say(t.seeWife)
    }
  }

  /** gText_DaycareCompatibility*: the decomp's four sentences. */
  fun compatibilityText(score: Int): String =
      when {
        score >= DaycareRules.MAX -> "The two seem to get along very well."
        score >= DaycareRules.MED -> "The two seem to get along."
        score >= DaycareRules.LOW -> "The two don't seem to like each other much."
        else -> "The two prefer to play with other POKéMON than each other."
      }

  /** FireRed's Route 5 day care man: one monster, no eggs. */
  suspend fun route5(ctx: ScriptContext) {
    val daycare = ctx.p().daycare
    val mon = daycare.route5(ctx.stored())
    if (mon != null) {
      val gained = daycare.levelsGained(ctx.stored(), mon)
      ctx.bufferString(1, ctx.nameOf(mon))
      ctx.bufferString(2, gained.toString())
      if (gained != 0) ctx.say(Route5_PokemonDayCare.MonHasGrownByXLevels)
      else ctx.say(Route5_PokemonDayCare.MonNeedsToSpendMoreTime)
      ctx.bufferString(2, DaycareRules.fee(gained).toString())
      if (!ctx.askYesNo(Route5_PokemonDayCare.OweMeXForMonsReturn)) {
        return ctx.say(Route5_PokemonDayCare.ComeAnytimeYouLike)
      }
      if (ctx.stored().pokemon.size >= PARTY_SIZE) return ctx.say(Route5_PokemonDayCare.YouveGotNoRoomForIt)
      if (ctx.stored().info.money < DaycareRules.fee(gained)) {
        return ctx.say(Route5_PokemonDayCare.DontHaveEnoughMoney)
      }
      val (refused, _) = daycare.withdraw(ctx.charId(), 0, route5 = true)
      if (refused != null) return ctx.p().tell(ctx.session, refused)
      ctx.p().progress.pushParty(ctx.session, ctx.charId())
      ctx.p().progress.push(ctx.session, ctx.charId())
      daycare.sendMoney(ctx.session, ctx.charId())
      ctx.say(Route5_PokemonDayCare.ThankYouHeresMon)
      ctx.say(Route5_PokemonDayCare.PlayerGotMonBack)
      return
    }
    if (!ctx.askYesNo(Route5_PokemonDayCare.WantMeToRaiseMon)) return ctx.say(Route5_PokemonDayCare.ComeAgain)
    if (ctx.stored().pokemon.count { !it.isEgg } == 1) return ctx.say(Route5_PokemonDayCare.OnlyHaveOneMonWithYou)
    ctx.say(Route5_PokemonDayCare.WhichMonShouldIRaise)
    val monId =
        ctx.p().pick(ctx.session, ctx.charId(), PICK_PARTY)
            ?: return ctx.say(Route5_PokemonDayCare.ComeAnytimeYouLike)
    val picked = ctx.stored().pokemon.firstOrNull { it.id == monId } ?: return
    if (ctx.stored().pokemon.none { it.id != monId && !it.isEgg && it.hp > 0 }) {
      return ctx.say(Route5_PokemonDayCare.WhatWillYouBattleWith)
    }
    val refused = daycare.deposit(ctx.charId(), monId, route5 = true)
    if (refused != null) return ctx.p().tell(ctx.session, refused)
    ctx.p().progress.pushParty(ctx.session, ctx.charId())
    ctx.p().progress.push(ctx.session, ctx.charId())
    ctx.bufferString(1, ctx.nameOf(picked))
    ctx.say(Route5_PokemonDayCare.LookAfterMonForAWhile)
    ctx.say(Route5_PokemonDayCare.ComeSeeMeInAWhile)
  }
}
