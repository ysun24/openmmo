package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.SlateportCity_OceanicMuseum_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SlateportCity_OceanicMuseum_1F_EventScript_EntranceAttendant : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(SlateportCity_OceanicMuseum_1F.PleaseEnjoyYourself)
}

internal object SlateportCity_OceanicMuseum_1F_EventScript_MuseumGrunt3 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(SlateportCity_OceanicMuseum_1F.WouldStuffHereMakeMeRich)
}

internal object SlateportCity_OceanicMuseum_1F_EventScript_MuseumGrunt4 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(SlateportCity_OceanicMuseum_1F.CanLearnForNefariousDeeds)
}

internal object SlateportCity_OceanicMuseum_1F_EventScript_MuseumGrunt2 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(SlateportCity_OceanicMuseum_1F.OurBossIsntHere)
}

internal object SlateportCity_OceanicMuseum_1F_EventScript_MuseumGrunt1 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(SlateportCity_OceanicMuseum_1F.AquaExistForGoodOfAll)
}

internal object SlateportCity_OceanicMuseum_1F_EventScript_MuseumGrunt5 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(SlateportCity_OceanicMuseum_1F.RustboroBungled)
}

internal object SlateportCity_OceanicMuseum_1F_EventScript_MuseumGrunt6 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(SlateportCity_OceanicMuseum_1F.DidntHaveMoney)
}

internal object SlateportCity_OceanicMuseum_1F_EventScript_MuseumPatron1 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(SlateportCity_OceanicMuseum_1F.LearnAboutSeaForBattling)
}

internal object SlateportCity_OceanicMuseum_1F_EventScript_MuseumPatron2 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(SlateportCity_OceanicMuseum_1F.SternIsRoleModel)
}

internal object SlateportCity_OceanicMuseum_1F_EventScript_MuseumPatron3 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(SlateportCity_OceanicMuseum_1F.MustBePokemonWeDontKnow)
}

internal object SlateportCity_OceanicMuseum_1F_EventScript_MuseumPatron4 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(SlateportCity_OceanicMuseum_1F.WantSeaPokemon)
}

internal object SlateportCity_OceanicMuseum_1F_EventScript_WhirlpoolExperiment : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(SlateportCity_OceanicMuseum_1F.WhirlpoolExperiment)
}

internal object SlateportCity_OceanicMuseum_1F_EventScript_WaterfallExperiment : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(SlateportCity_OceanicMuseum_1F.WaterfallExperiment)
}

internal object SlateportCity_OceanicMuseum_1F_EventScript_OceanSoilDisplay : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(SlateportCity_OceanicMuseum_1F.OceanSoilDisplay)
}

internal object SlateportCity_OceanicMuseum_1F_EventScript_BeachSandDisplay : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(SlateportCity_OceanicMuseum_1F.BeachSandDisplay)
}

internal object SlateportCity_OceanicMuseum_1F_EventScript_OceanicMinifact1 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(SlateportCity_OceanicMuseum_1F.OceanicMinifact1)
}

internal object SlateportCity_OceanicMuseum_1F_EventScript_OceanicMinifact2 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(SlateportCity_OceanicMuseum_1F.OceanicMinifact2)
}

internal object SlateportCity_OceanicMuseum_1F_EventScript_OceanicMinifact3 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(SlateportCity_OceanicMuseum_1F.OceanicMinifact3)
}

internal object SlateportCity_OceanicMuseum_1F_EventScript_FossilDisplay : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(SlateportCity_OceanicMuseum_1F.FossilDisplay)
}

internal object SlateportCity_OceanicMuseum_1F_EventScript_DepthMeasuringMachine : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(SlateportCity_OceanicMuseum_1F.DepthMeasuringMachine)
}

internal val SlateportCity_OceanicMuseum_1FScripts: Map<String, Script> =
    mapOf(
        "SlateportCity_OceanicMuseum_1F_EventScript_EntranceAttendant" to
            SlateportCity_OceanicMuseum_1F_EventScript_EntranceAttendant,
        "SlateportCity_OceanicMuseum_1F_EventScript_MuseumGrunt3" to
            SlateportCity_OceanicMuseum_1F_EventScript_MuseumGrunt3,
        "SlateportCity_OceanicMuseum_1F_EventScript_MuseumGrunt4" to
            SlateportCity_OceanicMuseum_1F_EventScript_MuseumGrunt4,
        "SlateportCity_OceanicMuseum_1F_EventScript_MuseumGrunt2" to
            SlateportCity_OceanicMuseum_1F_EventScript_MuseumGrunt2,
        "SlateportCity_OceanicMuseum_1F_EventScript_MuseumGrunt1" to
            SlateportCity_OceanicMuseum_1F_EventScript_MuseumGrunt1,
        "SlateportCity_OceanicMuseum_1F_EventScript_MuseumGrunt5" to
            SlateportCity_OceanicMuseum_1F_EventScript_MuseumGrunt5,
        "SlateportCity_OceanicMuseum_1F_EventScript_MuseumGrunt6" to
            SlateportCity_OceanicMuseum_1F_EventScript_MuseumGrunt6,
        "SlateportCity_OceanicMuseum_1F_EventScript_MuseumPatron1" to
            SlateportCity_OceanicMuseum_1F_EventScript_MuseumPatron1,
        "SlateportCity_OceanicMuseum_1F_EventScript_MuseumPatron2" to
            SlateportCity_OceanicMuseum_1F_EventScript_MuseumPatron2,
        "SlateportCity_OceanicMuseum_1F_EventScript_MuseumPatron3" to
            SlateportCity_OceanicMuseum_1F_EventScript_MuseumPatron3,
        "SlateportCity_OceanicMuseum_1F_EventScript_MuseumPatron4" to
            SlateportCity_OceanicMuseum_1F_EventScript_MuseumPatron4,
        "SlateportCity_OceanicMuseum_1F_EventScript_WhirlpoolExperiment" to
            SlateportCity_OceanicMuseum_1F_EventScript_WhirlpoolExperiment,
        "SlateportCity_OceanicMuseum_1F_EventScript_WaterfallExperiment" to
            SlateportCity_OceanicMuseum_1F_EventScript_WaterfallExperiment,
        "SlateportCity_OceanicMuseum_1F_EventScript_OceanSoilDisplay" to
            SlateportCity_OceanicMuseum_1F_EventScript_OceanSoilDisplay,
        "SlateportCity_OceanicMuseum_1F_EventScript_BeachSandDisplay" to
            SlateportCity_OceanicMuseum_1F_EventScript_BeachSandDisplay,
        "SlateportCity_OceanicMuseum_1F_EventScript_OceanicMinifact1" to
            SlateportCity_OceanicMuseum_1F_EventScript_OceanicMinifact1,
        "SlateportCity_OceanicMuseum_1F_EventScript_OceanicMinifact2" to
            SlateportCity_OceanicMuseum_1F_EventScript_OceanicMinifact2,
        "SlateportCity_OceanicMuseum_1F_EventScript_OceanicMinifact3" to
            SlateportCity_OceanicMuseum_1F_EventScript_OceanicMinifact3,
        "SlateportCity_OceanicMuseum_1F_EventScript_FossilDisplay" to
            SlateportCity_OceanicMuseum_1F_EventScript_FossilDisplay,
        "SlateportCity_OceanicMuseum_1F_EventScript_DepthMeasuringMachine" to
            SlateportCity_OceanicMuseum_1F_EventScript_DepthMeasuringMachine,
    )
