package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.LavenderTown_VolunteerPokemonHouse
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object LavenderTown_VolunteerPokemonHouse_EventScript_LittleBoy : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LavenderTown_VolunteerPokemonHouse.MonsNiceToHug)
}

internal object LavenderTown_VolunteerPokemonHouse_EventScript_Bookshelf : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(LavenderTown_VolunteerPokemonHouse.PokemonMagazinesLineShelf)
}

internal val LavenderTown_VolunteerPokemonHouseScripts: Map<String, Script> =
    mapOf(
        "LavenderTown_VolunteerPokemonHouse_EventScript_LittleBoy" to
            LavenderTown_VolunteerPokemonHouse_EventScript_LittleBoy,
        "LavenderTown_VolunteerPokemonHouse_EventScript_Bookshelf" to
            LavenderTown_VolunteerPokemonHouse_EventScript_Bookshelf,
    )
