package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.ThreeIsland_BondBridge
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object ThreeIsland_BondBridge_EventScript_BerryForestSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(ThreeIsland_BondBridge.BerryForestAhead)
}

internal object ThreeIsland_BondBridge_EventScript_BondBridgeSign : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(ThreeIsland_BondBridge.BondBridgeSign)
}

internal val ThreeIsland_BondBridgeScripts: Map<String, Script> =
    mapOf(
        "ThreeIsland_BondBridge_EventScript_BerryForestSign" to
            ThreeIsland_BondBridge_EventScript_BerryForestSign,
        "ThreeIsland_BondBridge_EventScript_BondBridgeSign" to
            ThreeIsland_BondBridge_EventScript_BondBridgeSign,
    )
