package de.fiereu.openmmo.server.game.minigame.gamecorner

import de.fiereu.openmmo.dialog.generated.hoenn.MauvilleCity_GameCorner as MauvilleText
import de.fiereu.openmmo.dialog.generated.kanto.CeladonCity_GameCorner as CeladonText
import de.fiereu.openmmo.dialog.generated.kanto.CeladonCity_GameCorner_PrizeRoom as PrizeText
import de.fiereu.openmmo.items.ItemDef
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.progression.Gen3Data
import de.fiereu.openmmo.server.game.minigame.MAX_COINS
import de.fiereu.openmmo.server.game.minigame.MinigameMenus
import de.fiereu.openmmo.server.game.minigame.MinigameRewards
import de.fiereu.openmmo.server.game.minigame.MinigameSave
import de.fiereu.openmmo.server.game.progression.ProgressKeys
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext
import de.fiereu.openmmo.server.game.storage.CharacterStore
import de.fiereu.openmmo.story.generated.kanto.KantoFlags
import java.util.concurrent.ThreadLocalRandom
import javax.inject.Inject
import javax.inject.Singleton

/** One prize of a prize counter. */
data class Prize(
    val label: String,
    val coins: Int,
    val dex: Int = 0,
    val level: Int = 0,
    val item: ItemDef? = null,
    val decor: String? = null
)

/**
 * The Game Corner scripts of both games, ported from the decomp (CeladonCity_GameCorner,
 * CeladonCity_GameCorner_PrizeRoom, MauvilleCity_GameCorner and data/scripts/roulette.inc) onto
 * this lane's specials. The decomp's multichoice boxes are [MinigameMenus] lists.
 */
@Singleton
class GameCornerScripts
@Inject
constructor(
    private val corner: GameCornerService,
    private val save: MinigameSave,
    private val menus: MinigameMenus,
    private val rewards: MinigameRewards,
    private val items: ItemRegistry,
    private val characters: CharacterStore,
) {
  private fun charId(ctx: ScriptContext) = ctx.playerEntityId

  private suspend fun closeBox(ctx: ScriptContext) = ctx.dialog.close(ctx.session, ctx.state)

  /** The decomp's checkcoins / addcoins with the coin box refreshed. */
  private suspend fun addCoins(ctx: ScriptContext, n: Int): Boolean {
    val ok = save.addCoins(charId(ctx), n)
    corner.pushCoins(ctx.session, charId(ctx))
    return ok
  }

  val scripts: Map<String, Script> by lazy {
    buildMap {
      // ---- Celadon (FireRed) ----
      put("CeladonCity_GameCorner_EventScript_CoinsClerk", Script { kantoCoinsClerk(it) })
      put(
          "CeladonCity_GameCorner_EventScript_Fisher",
          giver(
              KantoFlags.FLAG_GOT_10_COINS_FROM_GAMBLER,
              10,
              CeladonText.DoYouWantToPlay,
              CeladonText.Received10CoinsFromMan,
              CeladonText.DontNeedMyCoins,
              CeladonText.WinsComeAndGo))
      put(
          "CeladonCity_GameCorner_EventScript_Scientist",
          giver(
              KantoFlags.FLAG_GOT_20_COINS_FROM_GAMBLER,
              20,
              CeladonText.WantSomeCoins,
              CeladonText.Received20CoinsFromNiceGuy,
              CeladonText.YouHaveLotsOfCoins,
              CeladonText.NeedMoreCoinsForMonIWant))
      put(
          "CeladonCity_GameCorner_EventScript_Gentleman",
          giver(
              KantoFlags.FLAG_GOT_20_COINS_FROM_GAMBLER_2,
              20,
              CeladonText.HereAreSomeCoinsShoo,
              CeladonText.Received20CoinsFromMan,
              CeladonText.YouveGotPlentyCoins,
              CeladonText.WatchReelsClosely))
      for (i in 0..21) put(
          "CeladonCity_GameCorner_EventScript_SlotMachine$i", Script { kantoSlot(it) })
      for (i in 1..3) put(
          "CeladonCity_GameCorner_EventScript_UnusableSlotMachine$i",
          Script { it.sign(CeladonText.OutOfOrder) })
      put(
          "CeladonCity_GameCorner_PrizeRoom_EventScript_PrizeClerkMons",
          Script { kantoPrizes(it, KANTO_MONS) })
      put(
          "CeladonCity_GameCorner_PrizeRoom_EventScript_PrizeClerkTMs",
          Script { kantoPrizes(it, kantoTms()) })
      put(
          "CeladonCity_GameCorner_PrizeRoom_EventScript_PrizeClerkItems",
          Script { kantoPrizes(it, kantoItems()) })
      // ---- Mauville (Emerald) ----
      put("MauvilleCity_GameCorner_EventScript_CoinsClerk", Script { hoennCoinsClerk(it) })
      for (i in 0..11) put(
          "MauvilleCity_GameCorner_EventScript_SlotMachine$i", Script { hoennSlot(it, i) })
      put("Roulette_EventScript_Table1", Script { roulette(it, 0) })
      put("Roulette_EventScript_Table2", Script { roulette(it, 1) })
      put(
          "MauvilleCity_GameCorner_EventScript_PrizeCornerTMs",
          Script { hoennPrizes(it, hoennTms()) })
      put(
          "MauvilleCity_GameCorner_EventScript_PrizeCornerDolls",
          Script { hoennPrizes(it, HOENN_DOLLS) })
    }
  }

  // ---- coins ---------------------------------------------------------------------------------

  private suspend fun kantoCoinsClerk(ctx: ScriptContext) {
    val id = charId(ctx)
    corner.pushCoins(ctx.session, id)
    ctx.say(CeladonText.WelcomeBuySomeCoins)
    val choice =
        menus.choose(
            ctx.session,
            id,
            "GAME CORNER",
            listOf("50 COINS", "500 COINS", "NO THANKS"),
            listOf("¥1,000", "¥10,000", ""))
    if (choice !in 0..1) return ctx.say(CeladonText.ComePlaySometime)
    if (!corner.hasCoinCase(id)) return ctx.say(CeladonText.SorryDontHaveCoinCase)
    val (coins, price) = if (choice == 0) 50 to 1000 else 500 to 10000
    if (save.coins(id) >= MAX_COINS + 1 - coins) return ctx.say(CeladonText.CoinCaseIsFull)
    if (rewards.money(id) < price) return ctx.say(CeladonText.CantAffordCoins)
    if (!rewards.addMoney(ctx.session, id, -price)) return
    addCoins(ctx, coins)
    ctx.say(CeladonText.HereAreYourCoins)
  }

  private suspend fun hoennCoinsClerk(ctx: ScriptContext) {
    val id = charId(ctx)
    ctx.say(MauvilleText.ThisIsMauvilleGameCorner)
    if (!corner.hasCoinCase(id)) return ctx.say(MauvilleText.NeedCoinCaseForCoins)
    corner.pushCoins(ctx.session, id)
    ctx.say(MauvilleText.WereYouLookingForCoins)
    val choice =
        menus.choose(
            ctx.session,
            id,
            "COINS",
            listOf("50 COINS", "500 COINS", "CANCEL"),
            listOf("¥1,000", "¥10,000", ""))
    if (choice !in 0..1) return ctx.say(MauvilleText.DontNeedCoinsThen)
    val (coins, price) = if (choice == 0) 50 to 1000 else 500 to 10000
    if (save.coins(id) >= MAX_COINS + 1 - coins) return ctx.say(MauvilleText.CoinCaseIsFull)
    if (rewards.money(id) < price) return ctx.say(MauvilleText.DontHaveEnoughMoney)
    if (!rewards.addMoney(ctx.session, id, -price)) return
    addCoins(ctx, coins)
    ctx.say(MauvilleText.ThankYouHereAreYourCoins)
  }

  /** The Celadon gamblers who hand out coins once (FLAG_GOT_*_COINS_FROM_GAMBLER*). */
  private fun giver(
      flag: String,
      amount: Int,
      ask: CeladonText,
      received: CeladonText,
      full: CeladonText,
      after: CeladonText,
  ) = Script { ctx ->
    if (ctx.isFlagSet(flag)) return@Script ctx.say(after)
    ctx.say(ask)
    val id = charId(ctx)
    if (!corner.hasCoinCase(id)) return@Script ctx.say(CeladonText.DontHaveCoinCase)
    if (save.coins(id) >= MAX_COINS + 1 - amount) return@Script ctx.say(full)
    addCoins(ctx, amount)
    ctx.say(received)
    ctx.setFlag(flag)
  }

  // ---- slots / roulette ----------------------------------------------------------------------

  private suspend fun kantoSlot(ctx: ScriptContext) {
    val id = charId(ctx)
    if (!corner.hasCoinCase(id)) return ctx.sign(CeladonText.CoinCaseIsRequired)
    if (!ctx.askYesNo(CeladonText.SlotMachineWantToPlay)) return
    closeBox(ctx)
    // specialvar VAR_RESULT, GetRandomSlotMachineId; playslotmachine VAR_RESULT
    corner.playSlots(ctx.session, ctx.state, randomFrlgMachine())
  }

  private suspend fun hoennSlot(ctx: ScriptContext, index: Int) {
    val id = charId(ctx)
    if (!corner.hasCoinCase(id)) return ctx.sign(MauvilleText.CantPlayWithNoCoinCase)
    closeBox(ctx)
    corner.playSlots(ctx.session, ctx.state, hoennMachine(ctx, index))
  }

  private suspend fun roulette(ctx: ScriptContext, table: Int) {
    if (!corner.hasCoinCase(charId(ctx))) return ctx.sign(MauvilleText.CantPlayWithNoCoinCase)
    closeBox(ctx)
    corner.playRoulette(ctx.session, ctx.state, table)
  }

  fun randomFrlgMachine(): Int =
      FrlgSlotMachine.MACHINE_INDICES[
              ThreadLocalRandom.current().nextInt(FrlgSlotMachine.MACHINE_INDICES.size)]

  /** GetSlotMachineId with the Dewford trend seed the records lane keeps (0 until one exists). */
  fun hoennMachine(ctx: ScriptContext, index: Int): Int =
      EmeraldSlotMachine.machineIdFor(index, ctx.getVar(TREND_SEED_VAR))

  // ---- prizes --------------------------------------------------------------------------------

  private fun item(def: ItemDef?, coins: Int): Prize? =
      def?.let { d -> items.idOrNull(d)?.let { Prize(d.name.uppercase(), coins, item = d) } }

  private fun kantoTms() =
      listOfNotNull(
          item(Items.TM13, 4000),
          item(Items.TM23, 3500),
          item(Items.TM24, 4000),
          item(Items.TM30, 4500),
          item(Items.TM35, 4000))

  private fun kantoItems() =
      listOfNotNull(
          item(Items.SMOKE_BALL, 800),
          item(Items.MIRACLE_SEED, 1000),
          item(Items.CHARCOAL, 1000),
          item(Items.MYSTIC_WATER, 1000),
          item(Items.YELLOW_FLUTE, 1600))

  private fun hoennTms() =
      listOfNotNull(
          item(Items.TM32, 1500),
          item(Items.TM29, 3500),
          item(Items.TM35, 4000),
          item(Items.TM24, 4000),
          item(Items.TM13, 4000))

  private suspend fun kantoPrizes(ctx: ScriptContext, prizes: List<Prize>) {
    val id = charId(ctx)
    ctx.say(PrizeText.WeExchangeCoinsForPrizes)
    if (!corner.hasCoinCase(id)) return ctx.say(PrizeText.CoinCaseRequired)
    corner.pushCoins(ctx.session, id)
    while (true) {
      ctx.say(PrizeText.WhichPrize)
      val i =
          menus.choose(
              ctx.session,
              id,
              "PRIZES",
              prizes.map { it.label } + "NO THANKS",
              prizes.map { "%,d COINS".format(it.coins) } + "")
      val p = prizes.getOrNull(i) ?: return ctx.say(PrizeText.OhFineThen)
      if (p.dex != 0) ctx.bufferSpeciesName(1, p.dex) else ctx.bufferString(1, p.label)
      if (!ctx.askYesNo(PrizeText.YouWantPrize)) continue
      if (save.coins(id) < p.coins) {
        ctx.say(PrizeText.NeedMoreCoins)
        continue
      }
      if (!give(ctx, p)) return ctx.say(PrizeText.OopsNotEnoughRoom)
      addCoins(ctx, -p.coins)
      rewards.tell(ctx.session, "${ctx.playerName} obtained ${p.label}!")
    }
  }

  private suspend fun hoennPrizes(ctx: ScriptContext, prizes: List<Prize>) {
    val id = charId(ctx)
    ctx.say(MauvilleText.ExchangeCoinsForPrizes)
    if (!corner.hasCoinCase(id)) return ctx.say(MauvilleText.NeedCoinCaseGoNextDoor)
    corner.pushCoins(ctx.session, id)
    while (true) {
      ctx.say(MauvilleText.WhichPrize)
      val i =
          menus.choose(
              ctx.session,
              id,
              "PRIZES",
              prizes.map { it.label } + "CANCEL",
              prizes.map { "%,d COINS".format(it.coins) } + "")
      val p = prizes.getOrNull(i) ?: return ctx.say(MauvilleText.OhIsThatSo)
      ctx.bufferString(1, p.label)
      if (!ctx.askYesNo(
          if (p.item != null) MauvilleText.SoYourChoiceIsTheTMX else MauvilleText.SoYourChoiceIsX))
          continue
      if (save.coins(id) < p.coins) {
        ctx.say(MauvilleText.NotEnoughCoins)
        continue
      }
      if (!give(ctx, p))
          return ctx.say(
              if (p.decor != null) MauvilleText.NoRoomForPlacingDecor
              else MauvilleText.CantCarryAnyMore)
      addCoins(ctx, -p.coins)
      ctx.say(if (p.decor != null) MauvilleText.SendToYourHomePC else MauvilleText.HereYouGo)
    }
  }

  private suspend fun give(ctx: ScriptContext, p: Prize): Boolean {
    val id = charId(ctx)
    return when {
      p.dex != 0 -> rewards.giveMon(ctx.session, id, p.dex, p.level) != null
      p.item != null -> rewards.addItem(ctx.session, id, items.idOf(p.item), 1)
      p.decor != null -> {
        val d =
            Gen3Data.decorations.values.firstOrNull { it.name.equals(p.decor, true) }
                ?: return false
        val key = ProgressKeys.decor(d.id)
        val have = characters.getCharacter(id)?.storyVars?.get(key) ?: 0
        characters.setStoryVar(id, key, have + 1)
        true
      }
      else -> false
    }
  }

  companion object {
    /** Where the Dewford trend seed lives (openmmo var, written by the records lane). */
    const val TREND_SEED_VAR = "openmmo/DEWFORD_TREND_SEED"

    /** FireRed's CeladonCity_GameCorner_PrizeRoom mons: species, coins, level. */
    val KANTO_MONS =
        listOf(
            Prize("ABRA", 180, dex = 63, level = 9),
            Prize("CLEFAIRY", 500, dex = 35, level = 8),
            Prize("DRATINI", 2800, dex = 147, level = 18),
            Prize("SCYTHER", 5500, dex = 123, level = 25),
            Prize("PORYGON", 9999, dex = 137, level = 26),
        )

    /** MauvilleCity_GameCorner DOLL_COINS (1000) each. */
    val HOENN_DOLLS =
        listOf(
            Prize("TREECKO DOLL", 1000, decor = "TREECKO DOLL"),
            Prize("TORCHIC DOLL", 1000, decor = "TORCHIC DOLL"),
            Prize("MUDKIP DOLL", 1000, decor = "MUDKIP DOLL"),
        )
  }
}
