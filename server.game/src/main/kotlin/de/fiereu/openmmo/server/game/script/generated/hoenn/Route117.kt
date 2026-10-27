package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.Route117
import de.fiereu.openmmo.server.game.progression.DaycareScripts
import de.fiereu.openmmo.server.game.progression.HOENN_DAYCARE
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object Route117_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route117.ArentTheseFlowersPretty)
}

internal object Route117_EventScript_LittleBoy : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route117.AirIsTastyHere)
}

/**
 * Not ported yet. Decomp body:
 * ```
 * lock
 * faceplayer
 * special GetDaycareMonNicknames
 * specialvar VAR_RESULT, GetDaycareState
 * goto_if_eq VAR_RESULT, DAYCARE_EGG_WAITING, Route117_EventScript_DaycareEggWaiting
 * goto_if_eq VAR_RESULT, DAYCARE_ONE_MON, Route117_EventScript_CheckOnOneMon
 * goto_if_eq VAR_RESULT, DAYCARE_TWO_MONS, Route117_EventScript_CheckOnTwoMons
 * msgbox Route117_Text_SeeWifeIfYoudLikeMeToRaiseMon, MSGBOX_DEFAULT
 * release
 * end
 * ```
 */
internal object Route117_EventScript_DaycareMan : Script {
  override suspend fun run(ctx: ScriptContext) = DaycareScripts.man(ctx, HOENN_DAYCARE)
}

internal object Route117_EventScript_Girl : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(Route117.DayCarePokemonHadNewMove)
}

internal object Route117_EventScript_RouteSignVerdanturf : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route117.RouteSignVerdanturf)
}

internal object Route117_EventScript_RouteSignMauville : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route117.RouteSignMauville)
}

internal object Route117_EventScript_DayCareSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(Route117.DayCareSign)
}

internal val Route117Scripts: Map<String, Script> =
    mapOf(
        "Route117_EventScript_Woman" to Route117_EventScript_Woman,
        "Route117_EventScript_LittleBoy" to Route117_EventScript_LittleBoy,
        "Route117_EventScript_DaycareMan" to Route117_EventScript_DaycareMan,
        "Route117_EventScript_Girl" to Route117_EventScript_Girl,
        "Route117_EventScript_RouteSignVerdanturf" to Route117_EventScript_RouteSignVerdanturf,
        "Route117_EventScript_RouteSignMauville" to Route117_EventScript_RouteSignMauville,
        "Route117_EventScript_DayCareSign" to Route117_EventScript_DayCareSign,
    )
