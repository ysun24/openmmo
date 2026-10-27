package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.dialog.generated.hoenn.PlayersHouse_1F
import de.fiereu.openmmo.server.game.script.MovementStep.FACE_LEFT
import de.fiereu.openmmo.server.game.script.MovementStep.FACE_RIGHT
import de.fiereu.openmmo.server.game.script.MovementStep.WALK_UP
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext
import de.fiereu.openmmo.story.generated.hoenn.HoennFlags
import de.fiereu.openmmo.story.generated.hoenn.HoennVars

private const val LOCALID_HOUSE_MOM = 0

internal object LittlerootTown_BrendansHouse_1F_OnTransition : Script {
  override suspend fun run(ctx: ScriptContext) = positionMom(ctx, female = false)
}

internal object LittlerootTown_MaysHouse_1F_OnTransition : Script {
  override suspend fun run(ctx: ScriptContext) = positionMom(ctx, female = true)
}

private fun positionMom(ctx: ScriptContext, female: Boolean) {
  val position =
      when (ctx.getVar(HoennVars.VAR_LITTLEROOT_INTRO_STATE)) {
        3 -> if (female) 1 to 8 else 9 to 8
        5 -> if (female) 2 to 4 else 8 to 4
        6 -> if (female) 6 to 5 else 4 to 5
        else -> return
      }
  ctx.showNpcAt(LOCALID_HOUSE_MOM, position.first, position.second)
}

internal object LittlerootTown_BrendansHouse_1F_EventScript_EnterHouseMovingIn : Script {
  override suspend fun run(ctx: ScriptContext) = enterHouseMovingIn(ctx, female = false)
}

internal object LittlerootTown_MaysHouse_1F_EventScript_EnterHouseMovingIn : Script {
  override suspend fun run(ctx: ScriptContext) = enterHouseMovingIn(ctx, female = true)
}

private suspend fun enterHouseMovingIn(ctx: ScriptContext, female: Boolean) {
  positionMom(ctx, female)
  ctx.sayNpc(LOCALID_HOUSE_MOM, PlayersHouse_1F.IsntItNiceInHere)
  ctx.moveNpc(LOCALID_HOUSE_MOM, if (female) FACE_RIGHT else FACE_LEFT)
  ctx.moveSelf(if (female) FACE_LEFT else FACE_RIGHT)
  ctx.sayNpc(LOCALID_HOUSE_MOM, PlayersHouse_1F.MoversPokemonGoSetClock)
  ctx.setVar(HoennVars.VAR_LITTLEROOT_INTRO_STATE, 4)
  ctx.moveSelf(WALK_UP)
}

internal object LittlerootTown_BrendansHouse_1F_EventScript_GoUpstairsToSetClock : Script {
  override suspend fun run(ctx: ScriptContext) = goUpstairsToSetClock(ctx, female = false)
}

internal object LittlerootTown_MaysHouse_1F_EventScript_GoUpstairsToSetClock : Script {
  override suspend fun run(ctx: ScriptContext) = goUpstairsToSetClock(ctx, female = true)
}

private suspend fun goUpstairsToSetClock(ctx: ScriptContext, female: Boolean) {
  positionMom(ctx, female)
  ctx.sayNpc(LOCALID_HOUSE_MOM, PlayersHouse_1F.GoSetTheClock)
  ctx.moveSelf(WALK_UP)
  ctx.warp(
      regionId = 1,
      bankId = 51,
      mapId = if (female) 3 else 1,
      x = if (female) 1 else 7,
      y = 1,
      facing = Direction.DOWN,
  )
}

internal object LittlerootTown_BrendansHouse_1F_EventScript_PetalburgGymReport : Script {
  override suspend fun run(ctx: ScriptContext) = watchPetalburgReport(ctx, female = false)
}

internal object LittlerootTown_MaysHouse_1F_EventScript_PetalburgGymReport : Script {
  override suspend fun run(ctx: ScriptContext) = watchPetalburgReport(ctx, female = true)
}

private suspend fun watchPetalburgReport(ctx: ScriptContext, female: Boolean) {
  positionMom(ctx, female)
  ctx.sayNpc(LOCALID_HOUSE_MOM, PlayersHouse_1F.OhComeQuickly)
  ctx.sayNpc(LOCALID_HOUSE_MOM, PlayersHouse_1F.MaybeDadWillBeOn)
  ctx.sign(PlayersHouse_1F.ReportFromPetalburgGym)
  ctx.sayNpc(LOCALID_HOUSE_MOM, PlayersHouse_1F.ItsOverWeMissedHim)
  ctx.sayNpc(LOCALID_HOUSE_MOM, PlayersHouse_1F.GoIntroduceYourselfNextDoor)
  ctx.setFlag(HoennFlags.FLAG_SYS_TV_HOME)
  ctx.setVar(HoennVars.VAR_LITTLEROOT_INTRO_STATE, 7)
}

internal val LittlerootTown_BrendansHouse_1FScripts: Map<String, Script> =
    mapOf(
        "LittlerootTown_BrendansHouse_1F_OnTransition" to
            LittlerootTown_BrendansHouse_1F_OnTransition,
        "LittlerootTown_MaysHouse_1F_OnTransition" to LittlerootTown_MaysHouse_1F_OnTransition,
        "LittlerootTown_BrendansHouse_1F_EventScript_EnterHouseMovingIn" to
            LittlerootTown_BrendansHouse_1F_EventScript_EnterHouseMovingIn,
        "LittlerootTown_MaysHouse_1F_EventScript_EnterHouseMovingIn" to
            LittlerootTown_MaysHouse_1F_EventScript_EnterHouseMovingIn,
        "LittlerootTown_BrendansHouse_1F_EventScript_GoUpstairsToSetClock" to
            LittlerootTown_BrendansHouse_1F_EventScript_GoUpstairsToSetClock,
        "LittlerootTown_MaysHouse_1F_EventScript_GoUpstairsToSetClock" to
            LittlerootTown_MaysHouse_1F_EventScript_GoUpstairsToSetClock,
        "LittlerootTown_BrendansHouse_1F_EventScript_PetalburgGymReport" to
            LittlerootTown_BrendansHouse_1F_EventScript_PetalburgGymReport,
        "LittlerootTown_MaysHouse_1F_EventScript_PetalburgGymReport" to
            LittlerootTown_MaysHouse_1F_EventScript_PetalburgGymReport,
    )
