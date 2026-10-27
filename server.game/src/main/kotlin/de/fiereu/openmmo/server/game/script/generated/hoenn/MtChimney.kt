package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.MtChimney
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object MtChimney_EventScript_BusyMagmaGrunt5 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(MtChimney.DouseThemInFire)
}

internal object MtChimney_EventScript_BusyMagmaGrunt4 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(MtChimney.AquasNameSimilar)
}

internal object MtChimney_EventScript_BusyAquaGrunt2 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(MtChimney.LessHabitatForWaterPokemon)
}

internal object MtChimney_EventScript_BusyAquaGrunt1 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(MtChimney.MagmaOutnumbersUs)
}

internal object MtChimney_EventScript_BusyAquaGrunt3 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(MtChimney.MagmasNameSimilar)
}

internal object MtChimney_EventScript_BusyMagmaGrunt6 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(MtChimney.KeepMakingMoreLand)
}

internal object MtChimney_EventScript_AquaPoochyena : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(MtChimney.Bushaa)
}

internal object MtChimney_EventScript_MagmaPoochyena : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(MtChimney.Bufoh)
}

internal object MtChimney_EventScript_BusyMagmaGrunt2 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(MtChimney.MeteoritesPackAmazingPower)
}

internal object MtChimney_EventScript_BusyMagmaGrunt3 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(MtChimney.YouBetterNotMessWithUs)
}

internal object MtChimney_EventScript_BusyMagmaGrunt1 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(MtChimney.TeamAquaAlwaysMessingWithPlans)
}

internal object MtChimney_EventScript_RouteSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(MtChimney.RouteSign)
}

internal val MtChimneyScripts: Map<String, Script> =
    mapOf(
        "MtChimney_EventScript_BusyMagmaGrunt5" to MtChimney_EventScript_BusyMagmaGrunt5,
        "MtChimney_EventScript_BusyMagmaGrunt4" to MtChimney_EventScript_BusyMagmaGrunt4,
        "MtChimney_EventScript_BusyAquaGrunt2" to MtChimney_EventScript_BusyAquaGrunt2,
        "MtChimney_EventScript_BusyAquaGrunt1" to MtChimney_EventScript_BusyAquaGrunt1,
        "MtChimney_EventScript_BusyAquaGrunt3" to MtChimney_EventScript_BusyAquaGrunt3,
        "MtChimney_EventScript_BusyMagmaGrunt6" to MtChimney_EventScript_BusyMagmaGrunt6,
        "MtChimney_EventScript_AquaPoochyena" to MtChimney_EventScript_AquaPoochyena,
        "MtChimney_EventScript_MagmaPoochyena" to MtChimney_EventScript_MagmaPoochyena,
        "MtChimney_EventScript_BusyMagmaGrunt2" to MtChimney_EventScript_BusyMagmaGrunt2,
        "MtChimney_EventScript_BusyMagmaGrunt3" to MtChimney_EventScript_BusyMagmaGrunt3,
        "MtChimney_EventScript_BusyMagmaGrunt1" to MtChimney_EventScript_BusyMagmaGrunt1,
        "MtChimney_EventScript_RouteSign" to MtChimney_EventScript_RouteSign,
    )
