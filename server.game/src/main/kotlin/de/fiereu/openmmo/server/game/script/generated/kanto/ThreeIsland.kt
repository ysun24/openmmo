package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.ThreeIsland
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object ThreeIsland_EventScript_Woman : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(ThreeIsland.WouldntWantToSeeBikersHereAgain)
}

internal object ThreeIsland_EventScript_LittleBoy : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(ThreeIsland.WhenDodouEvolvesGoingToPlayGame)
}

internal object ThreeIsland_EventScript_Biker6 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(ThreeIsland.IslandBelongsToUs)
}

internal object ThreeIsland_EventScript_IslandSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(ThreeIsland.IslandSign)
}

internal val ThreeIslandScripts: Map<String, Script> =
    mapOf(
        "ThreeIsland_EventScript_Woman" to ThreeIsland_EventScript_Woman,
        "ThreeIsland_EventScript_LittleBoy" to ThreeIsland_EventScript_LittleBoy,
        "ThreeIsland_EventScript_Biker6" to ThreeIsland_EventScript_Biker6,
        "ThreeIsland_EventScript_IslandSign" to ThreeIsland_EventScript_IslandSign,
    )
