package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.BerryBlender
import de.fiereu.openmmo.dialog.generated.hoenn.LilycoveCity_ContestLobby
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object BerryBlender_EventScript_Blender2Man : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(BerryBlender.SetNewBlenderRecord)
}

internal object LilycoveCity_ContestLobby_EventScript_BlackBelt : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_ContestLobby.MasterRankHereICome)
}

internal object LilycoveCity_ContestLobby_EventScript_Girl : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_ContestLobby.WholeVarietyOfPokemonHere)
}

internal object LilycoveCity_ContestLobby_EventScript_Artist : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_ContestLobby.ContestFeastForEyes)
}

internal object LilycoveCity_ContestLobby_EventScript_Blender3Boy : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(BerryBlender.LetsGetBlendingAlready)
}

internal object BerryBlender_EventScript_Blender2Twin : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(BerryBlender.MakeDeliciousPokeblocks)
}

internal object LilycoveCity_ContestLobby_EventScript_Fisherman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_ContestLobby.LavishedCareOnMon)
}

internal object LilycoveCity_ContestLobby_EventScript_FatMan : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_ContestLobby.ToughContestIsExtreme)
}

internal object LilycoveCity_ContestLobby_EventScript_NinjaBoy : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_ContestLobby.MadePokeblocksWithFamily)
}

internal object BerryBlender_EventScript_Blender3PokefanF : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(BerryBlender.LookGoodAtBlendingJoinUs)
}

internal object LilycoveCity_ContestLobby_EventScript_Blender3Girl : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(BerryBlender.WhatKindOfPokeblockWillIGet)
}

internal val LilycoveCity_ContestLobbyScripts: Map<String, Script> =
    mapOf(
        "BerryBlender_EventScript_Blender2Man" to BerryBlender_EventScript_Blender2Man,
        "LilycoveCity_ContestLobby_EventScript_BlackBelt" to
            LilycoveCity_ContestLobby_EventScript_BlackBelt,
        "LilycoveCity_ContestLobby_EventScript_Girl" to LilycoveCity_ContestLobby_EventScript_Girl,
        "LilycoveCity_ContestLobby_EventScript_Artist" to
            LilycoveCity_ContestLobby_EventScript_Artist,
        "LilycoveCity_ContestLobby_EventScript_Blender3Boy" to
            LilycoveCity_ContestLobby_EventScript_Blender3Boy,
        "BerryBlender_EventScript_Blender2Twin" to BerryBlender_EventScript_Blender2Twin,
        "LilycoveCity_ContestLobby_EventScript_Fisherman" to
            LilycoveCity_ContestLobby_EventScript_Fisherman,
        "LilycoveCity_ContestLobby_EventScript_FatMan" to
            LilycoveCity_ContestLobby_EventScript_FatMan,
        "LilycoveCity_ContestLobby_EventScript_NinjaBoy" to
            LilycoveCity_ContestLobby_EventScript_NinjaBoy,
        "BerryBlender_EventScript_Blender3PokefanF" to BerryBlender_EventScript_Blender3PokefanF,
        "LilycoveCity_ContestLobby_EventScript_Blender3Girl" to
            LilycoveCity_ContestLobby_EventScript_Blender3Girl,
    )
