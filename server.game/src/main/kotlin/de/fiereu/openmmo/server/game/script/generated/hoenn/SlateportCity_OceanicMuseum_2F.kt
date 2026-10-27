package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.SlateportCity_OceanicMuseum_2F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SlateportCity_OceanicMuseum_2F_EventScript_MuseumPatron1 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(SlateportCity_OceanicMuseum_2F.RemindsMeOfAbandonedShip)
}

internal object SlateportCity_OceanicMuseum_2F_EventScript_MuseumPatron2 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(SlateportCity_OceanicMuseum_2F.DontRunInMuseum)
}

internal object SlateportCity_OceanicMuseum_2F_EventScript_WaterQualitySample1 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(SlateportCity_OceanicMuseum_2F.WaterQualitySample1)
}

internal object SlateportCity_OceanicMuseum_2F_EventScript_WaterQualitySample2 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(SlateportCity_OceanicMuseum_2F.WaterQualitySample2)
}

internal object SlateportCity_OceanicMuseum_2F_EventScript_SubmersibleReplica : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(SlateportCity_OceanicMuseum_2F.SumbersibleReplica)
}

internal object SlateportCity_OceanicMuseum_2F_EventScript_SubmarineReplica : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(SlateportCity_OceanicMuseum_2F.SubmarineReplica)
}

internal object SlateportCity_OceanicMuseum_2F_EventScript_SSTidalReplica : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(SlateportCity_OceanicMuseum_2F.SSTidalReplica)
}

internal object SlateportCity_OceanicMuseum_2F_EventScript_SSAnneReplica : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(SlateportCity_OceanicMuseum_2F.SSAnneReplica)
}

internal object SlateportCity_OceanicMuseum_2F_EventScript_SurfaceSeawaterDisplay : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(SlateportCity_OceanicMuseum_2F.SurfaceSeawaterDisplay)
}

internal object SlateportCity_OceanicMuseum_2F_EventScript_DeepSeawaterDisplay : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(SlateportCity_OceanicMuseum_2F.DeepSeawaterDisplay)
}

internal object SlateportCity_OceanicMuseum_2F_EventScript_HoennModel : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(SlateportCity_OceanicMuseum_2F.HoennModel)
}

internal object SlateportCity_OceanicMuseum_2F_EventScript_PressureExperiment : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(SlateportCity_OceanicMuseum_2F.PressureExperiment)
}

internal val SlateportCity_OceanicMuseum_2FScripts: Map<String, Script> =
    mapOf(
        "SlateportCity_OceanicMuseum_2F_EventScript_MuseumPatron1" to
            SlateportCity_OceanicMuseum_2F_EventScript_MuseumPatron1,
        "SlateportCity_OceanicMuseum_2F_EventScript_MuseumPatron2" to
            SlateportCity_OceanicMuseum_2F_EventScript_MuseumPatron2,
        "SlateportCity_OceanicMuseum_2F_EventScript_WaterQualitySample1" to
            SlateportCity_OceanicMuseum_2F_EventScript_WaterQualitySample1,
        "SlateportCity_OceanicMuseum_2F_EventScript_WaterQualitySample2" to
            SlateportCity_OceanicMuseum_2F_EventScript_WaterQualitySample2,
        "SlateportCity_OceanicMuseum_2F_EventScript_SubmersibleReplica" to
            SlateportCity_OceanicMuseum_2F_EventScript_SubmersibleReplica,
        "SlateportCity_OceanicMuseum_2F_EventScript_SubmarineReplica" to
            SlateportCity_OceanicMuseum_2F_EventScript_SubmarineReplica,
        "SlateportCity_OceanicMuseum_2F_EventScript_SSTidalReplica" to
            SlateportCity_OceanicMuseum_2F_EventScript_SSTidalReplica,
        "SlateportCity_OceanicMuseum_2F_EventScript_SSAnneReplica" to
            SlateportCity_OceanicMuseum_2F_EventScript_SSAnneReplica,
        "SlateportCity_OceanicMuseum_2F_EventScript_SurfaceSeawaterDisplay" to
            SlateportCity_OceanicMuseum_2F_EventScript_SurfaceSeawaterDisplay,
        "SlateportCity_OceanicMuseum_2F_EventScript_DeepSeawaterDisplay" to
            SlateportCity_OceanicMuseum_2F_EventScript_DeepSeawaterDisplay,
        "SlateportCity_OceanicMuseum_2F_EventScript_HoennModel" to
            SlateportCity_OceanicMuseum_2F_EventScript_HoennModel,
        "SlateportCity_OceanicMuseum_2F_EventScript_PressureExperiment" to
            SlateportCity_OceanicMuseum_2F_EventScript_PressureExperiment,
    )
