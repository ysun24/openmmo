package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.LilycoveCity_CoveLilyMotel_2F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object LilycoveCity_CoveLilyMotel_2F_EventScript_GraphicArtist : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_CoveLilyMotel_2F.ImTheGraphicArtist)
}

internal object LilycoveCity_CoveLilyMotel_2F_EventScript_FatMan : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(LilycoveCity_CoveLilyMotel_2F.GirlsAreCute)
}

internal object LilycoveCity_CoveLilyMotel_2F_EventScript_Programmer : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_CoveLilyMotel_2F.ImTheProgrammer)
}

internal object LilycoveCity_CoveLilyMotel_2F_EventScript_GameBoyKid : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_CoveLilyMotel_2F.NeverLeaveWithoutGameBoy)
}

internal object LilycoveCity_CoveLilyMotel_2F_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_CoveLilyMotel_2F.SeaBreezeTicklesHeart)
}

internal val LilycoveCity_CoveLilyMotel_2FScripts: Map<String, Script> =
    mapOf(
        "LilycoveCity_CoveLilyMotel_2F_EventScript_GraphicArtist" to
            LilycoveCity_CoveLilyMotel_2F_EventScript_GraphicArtist,
        "LilycoveCity_CoveLilyMotel_2F_EventScript_FatMan" to
            LilycoveCity_CoveLilyMotel_2F_EventScript_FatMan,
        "LilycoveCity_CoveLilyMotel_2F_EventScript_Programmer" to
            LilycoveCity_CoveLilyMotel_2F_EventScript_Programmer,
        "LilycoveCity_CoveLilyMotel_2F_EventScript_GameBoyKid" to
            LilycoveCity_CoveLilyMotel_2F_EventScript_GameBoyKid,
        "LilycoveCity_CoveLilyMotel_2F_EventScript_Woman" to
            LilycoveCity_CoveLilyMotel_2F_EventScript_Woman,
    )
