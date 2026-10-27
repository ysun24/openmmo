package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.LilycoveCity_LilycoveMuseum_2F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object LilycoveCity_LilycoveMuseum_2F_EventScript_Girl : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_LilycoveMuseum_2F.NewPaintingsSurprisedMe)
}

internal object LilycoveCity_LilycoveMuseum_2F_EventScript_ExpertM : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_LilycoveMuseum_2F.NewPaintingsRatherAmusing)
}

internal object LilycoveCity_LilycoveMuseum_2F_EventScript_RichBoy : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_LilycoveMuseum_2F.ThesePaintingsOfYourPokemon)
}

internal val LilycoveCity_LilycoveMuseum_2FScripts: Map<String, Script> =
    mapOf(
        "LilycoveCity_LilycoveMuseum_2F_EventScript_Girl" to
            LilycoveCity_LilycoveMuseum_2F_EventScript_Girl,
        "LilycoveCity_LilycoveMuseum_2F_EventScript_ExpertM" to
            LilycoveCity_LilycoveMuseum_2F_EventScript_ExpertM,
        "LilycoveCity_LilycoveMuseum_2F_EventScript_RichBoy" to
            LilycoveCity_LilycoveMuseum_2F_EventScript_RichBoy,
    )
