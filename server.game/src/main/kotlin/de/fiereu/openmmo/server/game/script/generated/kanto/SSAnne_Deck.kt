package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.SSAnne_Deck
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object SSAnne_Deck_EventScript_BaldingMan : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(SSAnne_Deck.FeelSeasick)
}

internal object SSAnne_Deck_EventScript_Sailor : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(SSAnne_Deck.ScrubbingDecksHardWork)
}

internal object SSAnne_Deck_EventScript_Youngster : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(SSAnne_Deck.ShipDepartingSoon)
}

internal val SSAnne_DeckScripts: Map<String, Script> =
    mapOf(
        "SSAnne_Deck_EventScript_BaldingMan" to SSAnne_Deck_EventScript_BaldingMan,
        "SSAnne_Deck_EventScript_Sailor" to SSAnne_Deck_EventScript_Sailor,
        "SSAnne_Deck_EventScript_Youngster" to SSAnne_Deck_EventScript_Youngster,
    )
