package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.PetalburgWoods
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.server.game.battle.BattleResult
import de.fiereu.openmmo.server.game.script.MovementStep.FACE_UP
import de.fiereu.openmmo.server.game.script.MovementStep.SET_INVISIBLE
import de.fiereu.openmmo.server.game.script.MovementStep.WALK_DOWN
import de.fiereu.openmmo.server.game.script.MovementStep.WALK_UP
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext
import de.fiereu.openmmo.story.generated.hoenn.HoennFlags
import de.fiereu.openmmo.story.generated.hoenn.HoennVars

private const val LOCALID_AQUA_GRUNT = 2
private const val LOCALID_DEVON_RESEARCHER = 3
private const val POOCHYENA = 261

internal object PetalburgWoods_EventScript_DevonResearcherLeft : Script {
  override suspend fun run(ctx: ScriptContext) = devonResearcherEvent(ctx)
}

internal object PetalburgWoods_EventScript_DevonResearcherRight : Script {
  override suspend fun run(ctx: ScriptContext) = devonResearcherEvent(ctx)
}

private suspend fun devonResearcherEvent(ctx: ScriptContext) {
  if (ctx.getVar(HoennVars.VAR_PETALBURG_WOODS_STATE) != 0) return
  ctx.moveNpc(LOCALID_DEVON_RESEARCHER, FACE_UP)
  ctx.sayNpc(LOCALID_DEVON_RESEARCHER, PetalburgWoods.NotAOneToBeFound)
  ctx.moveNpc(LOCALID_DEVON_RESEARCHER, WALK_DOWN, WALK_DOWN)
  ctx.sayNpc(LOCALID_DEVON_RESEARCHER, PetalburgWoods.HaveYouSeenShroomish)
  ctx.moveNpc(LOCALID_AQUA_GRUNT, WALK_DOWN, WALK_DOWN)
  ctx.sayNpc(LOCALID_AQUA_GRUNT, PetalburgWoods.IWasGoingToAmbushYou)
  ctx.moveNpc(LOCALID_AQUA_GRUNT, WALK_DOWN, WALK_DOWN)
  ctx.sayNpc(LOCALID_AQUA_GRUNT, PetalburgWoods.HandOverThosePapers)
  ctx.sayNpc(LOCALID_DEVON_RESEARCHER, PetalburgWoods.YouHaveToHelpMe)
  ctx.moveNpc(LOCALID_AQUA_GRUNT, WALK_DOWN)
  ctx.sayNpc(LOCALID_AQUA_GRUNT, PetalburgWoods.NoOneCrossesTeamAqua)
  if (ctx.battle(POOCHYENA, 9, 33, 336, 28) != BattleResult.VICTORY) return
  ctx.sayNpc(LOCALID_AQUA_GRUNT, PetalburgWoods.YoureKiddingMe)
  ctx.sayNpc(LOCALID_AQUA_GRUNT, PetalburgWoods.YouveGotSomeNerve)
  ctx.moveNpc(
      LOCALID_AQUA_GRUNT,
      WALK_UP,
      WALK_UP,
      WALK_UP,
      WALK_UP,
      WALK_UP,
      SET_INVISIBLE,
  )
  ctx.setFlag(HoennFlags.FLAG_HIDE_PETALBURG_WOODS_AQUA_GRUNT)
  ctx.sayNpc(LOCALID_DEVON_RESEARCHER, PetalburgWoods.ThatWasAwfullyClose)
  ctx.giveItem(Items.GREAT_BALL)
  ctx.sayNpc(LOCALID_DEVON_RESEARCHER, PetalburgWoods.TeamAquaAfterSomethingInRustboro)
  ctx.sayNpc(LOCALID_DEVON_RESEARCHER, PetalburgWoods.ICantBeWastingTime)
  ctx.moveNpc(
      LOCALID_DEVON_RESEARCHER,
      WALK_UP,
      WALK_UP,
      WALK_UP,
      WALK_UP,
      WALK_UP,
      SET_INVISIBLE,
  )
  ctx.setFlag(HoennFlags.FLAG_HIDE_PETALBURG_WOODS_DEVON_EMPLOYEE)
  ctx.setVar(HoennVars.VAR_PETALBURG_WOODS_STATE, 1)
}

internal object PetalburgWoods_EventScript_Boy1 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(PetalburgWoods.StayOutOfTallGrass)
}

internal object PetalburgWoods_EventScript_Boy2 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(PetalburgWoods.HiddenItemsExplanation)
}

internal object PetalburgWoods_EventScript_Sign1 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(PetalburgWoods.TrainerTipsExperience)
}

internal object PetalburgWoods_EventScript_Sign2 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(PetalburgWoods.TrainerTipsPP)
}

internal val PetalburgWoodsScripts: Map<String, Script> =
    mapOf(
        "PetalburgWoods_EventScript_DevonResearcherLeft" to
            PetalburgWoods_EventScript_DevonResearcherLeft,
        "PetalburgWoods_EventScript_DevonResearcherRight" to
            PetalburgWoods_EventScript_DevonResearcherRight,
        "PetalburgWoods_EventScript_Boy1" to PetalburgWoods_EventScript_Boy1,
        "PetalburgWoods_EventScript_Boy2" to PetalburgWoods_EventScript_Boy2,
        "PetalburgWoods_EventScript_Sign1" to PetalburgWoods_EventScript_Sign1,
        "PetalburgWoods_EventScript_Sign2" to PetalburgWoods_EventScript_Sign2,
    )
