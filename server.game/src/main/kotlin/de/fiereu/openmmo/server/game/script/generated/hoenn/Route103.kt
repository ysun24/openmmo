package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Route103
import de.fiereu.openmmo.server.game.battle.BattleResult
import de.fiereu.openmmo.server.game.script.MovementStep.FACE_DOWN
import de.fiereu.openmmo.server.game.script.MovementStep.WALK_DOWN
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext
import de.fiereu.openmmo.story.generated.hoenn.HoennFlags
import de.fiereu.openmmo.story.generated.hoenn.HoennVars

private const val LOCALID_RIVAL = 1

internal object Route103_EventScript_Man : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route103.ShortcutToOldale)
}

internal object Route103_EventScript_Rival : Script {
  override suspend fun run(ctx: ScriptContext) {
    if (ctx.isFlagSet(HoennFlags.FLAG_DEFEATED_RIVAL_ROUTE103)) return
    val femalePlayer = ctx.isFemale
    if (femalePlayer) {
      ctx.say(Route103.BrendanRoute103Pokemon)
      ctx.say(Route103.BrendanLetsBattle)
    } else {
      ctx.say(Route103.MayRoute103Pokemon)
      ctx.say(Route103.MayLetsBattle)
    }

    val opponent =
        when (ctx.getVar(HoennVars.VAR_STARTER_MON)) {
          0 -> 255 // Treecko player -> Torchic rival
          1 -> 258 // Torchic player -> Mudkip rival
          else -> 252 // Mudkip player -> Treecko rival
        }
    val moves =
        when (opponent) {
          252 -> intArrayOf(1, 43)
          255 -> intArrayOf(10, 45)
          else -> intArrayOf(33, 45)
        }
    if (ctx.battle(opponent, 5, *moves) != BattleResult.VICTORY) return

    if (femalePlayer) {
      ctx.say(Route103.BrendanDefeated)
      ctx.say(Route103.BrendanTimeToHeadBack)
    } else {
      ctx.say(Route103.MayDefeated)
      ctx.say(Route103.MayTimeToHeadBack)
    }
    ctx.moveNpc(LOCALID_RIVAL, WALK_DOWN, WALK_DOWN, WALK_DOWN, WALK_DOWN, FACE_DOWN)
    ctx.setFlag(HoennFlags.FLAG_HIDE_ROUTE_103_RIVAL)
    ctx.setVar(HoennVars.VAR_BIRCH_LAB_STATE, 4)
    ctx.clearFlag(HoennFlags.FLAG_HIDE_LITTLEROOT_TOWN_BIRCHS_LAB_RIVAL)
    ctx.setFlag(HoennFlags.FLAG_DEFEATED_RIVAL_ROUTE103)
    ctx.setVar(HoennVars.VAR_OLDALE_RIVAL_STATE, 1)
    ctx.clearFlag(HoennFlags.FLAG_HIDE_OLDALE_TOWN_RIVAL)
  }
}

internal object Route103_EventScript_Boy : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route103.ShouldHaveBroughtPotion)
}

internal object Route103_EventScript_RouteSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route103.RouteSign)
}

internal val Route103Scripts: Map<String, Script> =
    mapOf(
        "Route103_EventScript_Man" to Route103_EventScript_Man,
        "Route103_EventScript_Rival" to Route103_EventScript_Rival,
        "Route103_EventScript_Boy" to Route103_EventScript_Boy,
        "Route103_EventScript_RouteSign" to Route103_EventScript_RouteSign,
    )
