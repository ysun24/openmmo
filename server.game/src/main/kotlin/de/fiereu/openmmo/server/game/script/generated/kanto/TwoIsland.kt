package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Misc
import de.fiereu.openmmo.dialog.generated.kanto.TwoIsland
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext
import de.fiereu.openmmo.story.generated.kanto.KantoFlags
import de.fiereu.openmmo.story.generated.kanto.KantoVars

/** The decomp's pokemart clerk, with its stock from the item list beside it. */
internal object TwoIsland_EventScript_Clerk : Script {
  override suspend fun run(ctx: ScriptContext) {
    if (ctx.getVar(KantoVars.VAR_MAP_SCENE_TWO_ISLAND) == 4) {
      if (ctx.isFlagSet(KantoFlags.FLAG_TWO_ISLAND_SHOP_EXPANDED_3)) {
        ctx.say(Misc.Text_MayIHelpYou)
        if (ctx.getVar(KantoVars.VAR_MAP_SCENE_TWO_ISLAND) == 4) {
          ctx.pokemart(
              Items.ULTRA_BALL,
              Items.REPEAT_BALL,
              Items.TIMER_BALL,
              Items.LEMONADE,
              Items.SODA_POP,
              Items.FRESH_WATER,
              Items.MOOMOO_MILK,
              Items.LAVA_COOKIE)
          return
        }
        if (ctx.getVar(KantoVars.VAR_MAP_SCENE_TWO_ISLAND) == 3) {
          ctx.pokemart(
              Items.ULTRA_BALL,
              Items.GREAT_BALL,
              Items.LEMONADE,
              Items.SODA_POP,
              Items.FRESH_WATER,
              Items.MOOMOO_MILK)
          return
        }
        if (ctx.getVar(KantoVars.VAR_MAP_SCENE_TWO_ISLAND) == 2) {
          ctx.pokemart(Items.ULTRA_BALL, Items.GREAT_BALL, Items.SODA_POP, Items.FRESH_WATER)
          return
        }
        ctx.pokemart(Items.GREAT_BALL, Items.FRESH_WATER)
        return
      }
      ctx.setFlag(KantoFlags.FLAG_TWO_ISLAND_SHOP_EXPANDED_3)
      ctx.say(TwoIsland.BringingItemsFromDistantLands)
      ctx.pokemart(
          Items.ULTRA_BALL,
          Items.REPEAT_BALL,
          Items.TIMER_BALL,
          Items.LEMONADE,
          Items.SODA_POP,
          Items.FRESH_WATER,
          Items.MOOMOO_MILK,
          Items.LAVA_COOKIE)
      return
    }
    if (ctx.getVar(KantoVars.VAR_MAP_SCENE_TWO_ISLAND) == 3) {
      if (ctx.isFlagSet(KantoFlags.FLAG_TWO_ISLAND_SHOP_EXPANDED_2)) {
        ctx.say(Misc.Text_MayIHelpYou)
        if (ctx.getVar(KantoVars.VAR_MAP_SCENE_TWO_ISLAND) == 4) {
          ctx.pokemart(
              Items.ULTRA_BALL,
              Items.REPEAT_BALL,
              Items.TIMER_BALL,
              Items.LEMONADE,
              Items.SODA_POP,
              Items.FRESH_WATER,
              Items.MOOMOO_MILK,
              Items.LAVA_COOKIE)
          return
        }
        if (ctx.getVar(KantoVars.VAR_MAP_SCENE_TWO_ISLAND) == 3) {
          ctx.pokemart(
              Items.ULTRA_BALL,
              Items.GREAT_BALL,
              Items.LEMONADE,
              Items.SODA_POP,
              Items.FRESH_WATER,
              Items.MOOMOO_MILK)
          return
        }
        if (ctx.getVar(KantoVars.VAR_MAP_SCENE_TWO_ISLAND) == 2) {
          ctx.pokemart(Items.ULTRA_BALL, Items.GREAT_BALL, Items.SODA_POP, Items.FRESH_WATER)
          return
        }
        ctx.pokemart(Items.GREAT_BALL, Items.FRESH_WATER)
        return
      }
      ctx.setFlag(KantoFlags.FLAG_TWO_ISLAND_SHOP_EXPANDED_2)
      ctx.say(TwoIsland.HopeYouGiveItYourBest)
      ctx.pokemart(
          Items.ULTRA_BALL,
          Items.GREAT_BALL,
          Items.LEMONADE,
          Items.SODA_POP,
          Items.FRESH_WATER,
          Items.MOOMOO_MILK)
      return
    }
    if (ctx.getVar(KantoVars.VAR_MAP_SCENE_TWO_ISLAND) == 2) {
      if (ctx.isFlagSet(KantoFlags.FLAG_TWO_ISLAND_SHOP_EXPANDED_1)) {
        ctx.say(Misc.Text_MayIHelpYou)
        if (ctx.getVar(KantoVars.VAR_MAP_SCENE_TWO_ISLAND) == 4) {
          ctx.pokemart(
              Items.ULTRA_BALL,
              Items.REPEAT_BALL,
              Items.TIMER_BALL,
              Items.LEMONADE,
              Items.SODA_POP,
              Items.FRESH_WATER,
              Items.MOOMOO_MILK,
              Items.LAVA_COOKIE)
          return
        }
        if (ctx.getVar(KantoVars.VAR_MAP_SCENE_TWO_ISLAND) == 3) {
          ctx.pokemart(
              Items.ULTRA_BALL,
              Items.GREAT_BALL,
              Items.LEMONADE,
              Items.SODA_POP,
              Items.FRESH_WATER,
              Items.MOOMOO_MILK)
          return
        }
        if (ctx.getVar(KantoVars.VAR_MAP_SCENE_TWO_ISLAND) == 2) {
          ctx.pokemart(Items.ULTRA_BALL, Items.GREAT_BALL, Items.SODA_POP, Items.FRESH_WATER)
          return
        }
        ctx.pokemart(Items.GREAT_BALL, Items.FRESH_WATER)
        return
      }
      ctx.setFlag(KantoFlags.FLAG_TWO_ISLAND_SHOP_EXPANDED_1)
      ctx.say(TwoIsland.AddedMerchandiseForLostelle)
      ctx.pokemart(Items.ULTRA_BALL, Items.GREAT_BALL, Items.SODA_POP, Items.FRESH_WATER)
      return
    }
    if (ctx.isFlagSet(KantoFlags.FLAG_TWO_ISLAND_SHOP_INTRODUCED)) {
      ctx.say(Misc.Text_MayIHelpYou)
      if (ctx.getVar(KantoVars.VAR_MAP_SCENE_TWO_ISLAND) == 4) {
        ctx.pokemart(
            Items.ULTRA_BALL,
            Items.REPEAT_BALL,
            Items.TIMER_BALL,
            Items.LEMONADE,
            Items.SODA_POP,
            Items.FRESH_WATER,
            Items.MOOMOO_MILK,
            Items.LAVA_COOKIE)
        return
      }
      if (ctx.getVar(KantoVars.VAR_MAP_SCENE_TWO_ISLAND) == 3) {
        ctx.pokemart(
            Items.ULTRA_BALL,
            Items.GREAT_BALL,
            Items.LEMONADE,
            Items.SODA_POP,
            Items.FRESH_WATER,
            Items.MOOMOO_MILK)
        return
      }
      if (ctx.getVar(KantoVars.VAR_MAP_SCENE_TWO_ISLAND) == 2) {
        ctx.pokemart(Items.ULTRA_BALL, Items.GREAT_BALL, Items.SODA_POP, Items.FRESH_WATER)
        return
      }
      ctx.pokemart(Items.GREAT_BALL, Items.FRESH_WATER)
      return
    }
    ctx.setFlag(KantoFlags.FLAG_TWO_ISLAND_SHOP_INTRODUCED)
    ctx.say(TwoIsland.WelcomeToShopMerchandiseLimited)
    ctx.pokemart(Items.GREAT_BALL, Items.FRESH_WATER)
  }
}

internal object TwoIsland_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(TwoIsland.ShopkeepersBrotherWorksGameCorner)
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lock
 * faceplayer
 * famechecker FAMECHECKER_BRUNO, 4
 * msgbox TwoIsland_Text_BrunoCameToIslandWhileBack
 * release
 * end
 * ```
 */
internal object TwoIsland_EventScript_Beauty : Script {
  override suspend fun run(ctx: ScriptContext) = TODO("port TwoIsland_EventScript_Beauty")
}

internal object TwoIsland_EventScript_Sailor : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(TwoIsland.FellowMovedFromCeladonCity)
}

internal object TwoIsland_EventScript_PokeManiac : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(TwoIsland.BuyRareItemsHere)
}

/**
 * Not ported yet. Decomp body:
 * ```
 * finditem ITEM_REVIVE
 * end
 * ```
 */
internal object TwoIsland_EventScript_ItemRevive : Script {
  override suspend fun run(ctx: ScriptContext) = TODO("port TwoIsland_EventScript_ItemRevive")
}

internal object TwoIsland_EventScript_LittleBoy : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(TwoIsland.HaveYouTriedJumpingGame)
}

internal object TwoIsland_EventScript_Boy : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(TwoIsland.OldWomanLivesOutOnCape)
}

internal object TwoIsland_EventScript_IslandSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(TwoIsland.IslandSign)
}

internal object TwoIsland_EventScript_JoyfulGameCornerSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(TwoIsland.JoyfulGameCornerSign)
}

internal object TwoIsland_EventScript_FastCurrentSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(TwoIsland.DangerFastCurrent)
}

internal val TwoIslandScripts: Map<String, Script> =
    mapOf(
        "TwoIsland_EventScript_Clerk" to TwoIsland_EventScript_Clerk,
        "TwoIsland_EventScript_Woman" to TwoIsland_EventScript_Woman,
        "TwoIsland_EventScript_Beauty" to TwoIsland_EventScript_Beauty,
        "TwoIsland_EventScript_Sailor" to TwoIsland_EventScript_Sailor,
        "TwoIsland_EventScript_PokeManiac" to TwoIsland_EventScript_PokeManiac,
        "TwoIsland_EventScript_ItemRevive" to TwoIsland_EventScript_ItemRevive,
        "TwoIsland_EventScript_LittleBoy" to TwoIsland_EventScript_LittleBoy,
        "TwoIsland_EventScript_Boy" to TwoIsland_EventScript_Boy,
        "TwoIsland_EventScript_IslandSign" to TwoIsland_EventScript_IslandSign,
        "TwoIsland_EventScript_JoyfulGameCornerSign" to TwoIsland_EventScript_JoyfulGameCornerSign,
        "TwoIsland_EventScript_FastCurrentSign" to TwoIsland_EventScript_FastCurrentSign,
    )
