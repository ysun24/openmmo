package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.RustboroCity_PokemonSchool
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object RustboroCity_PokemonSchool_EventScript_GameboyKid1 : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.say(RustboroCity_PokemonSchool.TradingRightNow)
}

internal object RustboroCity_PokemonSchool_EventScript_GameboyKid2 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(RustboroCity_PokemonSchool.AlwaysWantedSeedot)
}

internal object RustboroCity_PokemonSchool_EventScript_RichBoy : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(RustboroCity_PokemonSchool.PokemontCantUseManMadeItems)
}

internal object RustboroCity_PokemonSchool_EventScript_Lass : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(RustboroCity_PokemonSchool.ConfusedPokemonAttacksItself)
}

internal object RustboroCity_PokemonSchool_EventScript_SchoolKidM : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(RustboroCity_PokemonSchool.PokemonHealItselfWithBerry)
}

internal object RustboroCity_PokemonSchool_EventScript_StudentNotebook : Script {
  override suspend fun run(ctx: ScriptContext) = ctx.sign(RustboroCity_PokemonSchool.StudentsNotes)
}

internal val RustboroCity_PokemonSchoolScripts: Map<String, Script> =
    mapOf(
        "RustboroCity_PokemonSchool_EventScript_GameboyKid1" to
            RustboroCity_PokemonSchool_EventScript_GameboyKid1,
        "RustboroCity_PokemonSchool_EventScript_GameboyKid2" to
            RustboroCity_PokemonSchool_EventScript_GameboyKid2,
        "RustboroCity_PokemonSchool_EventScript_RichBoy" to
            RustboroCity_PokemonSchool_EventScript_RichBoy,
        "RustboroCity_PokemonSchool_EventScript_Lass" to
            RustboroCity_PokemonSchool_EventScript_Lass,
        "RustboroCity_PokemonSchool_EventScript_SchoolKidM" to
            RustboroCity_PokemonSchool_EventScript_SchoolKidM,
        "RustboroCity_PokemonSchool_EventScript_StudentNotebook" to
            RustboroCity_PokemonSchool_EventScript_StudentNotebook,
    )
