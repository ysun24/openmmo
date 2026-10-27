package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.LilycoveCity_LilycoveMuseum
import de.fiereu.openmmo.dialog.generated.hoenn.LilycoveCity_LilycoveMuseum_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

internal object LilycoveCity_LilycoveMuseum_1F_EventScript_Greeter : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(LilycoveCity_LilycoveMuseum_1F.WelcomeToLilycoveMuseum)
}

internal object LilycoveCity_LilycoveMuseum_1F_EventScript_SchoolKidM : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_LilycoveMuseum_1F.MustntForgetLoveForFineArts)
}

internal object LilycoveCity_LilycoveMuseum_1F_EventScript_Artist1 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_LilycoveMuseum_1F.ThisMuseumIsInspiration)
}

internal object LilycoveCity_LilycoveMuseum_1F_EventScript_NinjaBoy : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(LilycoveCity_LilycoveMuseum_1F.ThisLadyIsPretty)
}

internal object LilycoveCity_LilycoveMuseum_1F_EventScript_Woman1 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(LilycoveCity_LilycoveMuseum_1F.ThisPokemonIsAdorable)
}

internal object LilycoveCity_LilycoveMuseum_1F_EventScript_Woman2 : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_LilycoveMuseum_1F.HeardMuseumGotNewPaintings)
}

internal object LilycoveCity_LilycoveMuseum_1F_EventScript_FatMan : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_LilycoveMuseum_1F.MuseumTouristDestination)
}

internal object LilycoveCity_LilycoveMuseum_1F_EventScript_PsychicM : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(LilycoveCity_LilycoveMuseum_1F.CuratorHasBeenCheerful)
}

internal object LilycoveCity_LilycoveMuseum_1F_EventScript_FantasyPainting : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(LilycoveCity_LilycoveMuseum_1F.OddLandscapeFantasticScenery)
}

internal object LilycoveCity_LilycoveMuseum_1F_EventScript_BerryPainting : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(LilycoveCity_LilycoveMuseum_1F.PaintingOfBerries)
}

internal object LilycoveCity_LilycoveMuseum_1F_EventScript_OldPainting : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(LilycoveCity_LilycoveMuseum_1F.VeryOldPainting)
}

internal object LilycoveCity_LilycoveMuseum_1F_EventScript_WomanPainting : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(LilycoveCity_LilycoveMuseum_1F.PaintingOfBeautifulWoman)
}

internal object LilycoveCity_LilycoveMuseum_EventScript_BirdSculpture : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(LilycoveCity_LilycoveMuseum.BirdPokemonSculptureReplica)
}

internal object LilycoveCity_LilycoveMuseum_1F_EventScript_GrassPokemonPainting : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(LilycoveCity_LilycoveMuseum_1F.PaintingOfGrassPokemon)
}

internal object LilycoveCity_LilycoveMuseum_1F_EventScript_StoneTablet : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(LilycoveCity_LilycoveMuseum_1F.StoneTabletWithAncientText)
}

internal object LilycoveCity_LilycoveMuseum_1F_EventScript_LegendaryPokemonPainting : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(LilycoveCity_LilycoveMuseum_1F.PaintingOfLegendaryPokemon)
}

internal object LilycoveCity_LilycoveMuseum_1F_EventScript_PokeBallSculpture : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.sign(LilycoveCity_LilycoveMuseum_1F.BigPokeBallCarvedFromStone)
}

internal val LilycoveCity_LilycoveMuseum_1FScripts: Map<String, Script> =
    mapOf(
        "LilycoveCity_LilycoveMuseum_1F_EventScript_Greeter" to
            LilycoveCity_LilycoveMuseum_1F_EventScript_Greeter,
        "LilycoveCity_LilycoveMuseum_1F_EventScript_SchoolKidM" to
            LilycoveCity_LilycoveMuseum_1F_EventScript_SchoolKidM,
        "LilycoveCity_LilycoveMuseum_1F_EventScript_Artist1" to
            LilycoveCity_LilycoveMuseum_1F_EventScript_Artist1,
        "LilycoveCity_LilycoveMuseum_1F_EventScript_NinjaBoy" to
            LilycoveCity_LilycoveMuseum_1F_EventScript_NinjaBoy,
        "LilycoveCity_LilycoveMuseum_1F_EventScript_Woman1" to
            LilycoveCity_LilycoveMuseum_1F_EventScript_Woman1,
        "LilycoveCity_LilycoveMuseum_1F_EventScript_Woman2" to
            LilycoveCity_LilycoveMuseum_1F_EventScript_Woman2,
        "LilycoveCity_LilycoveMuseum_1F_EventScript_FatMan" to
            LilycoveCity_LilycoveMuseum_1F_EventScript_FatMan,
        "LilycoveCity_LilycoveMuseum_1F_EventScript_PsychicM" to
            LilycoveCity_LilycoveMuseum_1F_EventScript_PsychicM,
        "LilycoveCity_LilycoveMuseum_1F_EventScript_FantasyPainting" to
            LilycoveCity_LilycoveMuseum_1F_EventScript_FantasyPainting,
        "LilycoveCity_LilycoveMuseum_1F_EventScript_BerryPainting" to
            LilycoveCity_LilycoveMuseum_1F_EventScript_BerryPainting,
        "LilycoveCity_LilycoveMuseum_1F_EventScript_OldPainting" to
            LilycoveCity_LilycoveMuseum_1F_EventScript_OldPainting,
        "LilycoveCity_LilycoveMuseum_1F_EventScript_WomanPainting" to
            LilycoveCity_LilycoveMuseum_1F_EventScript_WomanPainting,
        "LilycoveCity_LilycoveMuseum_EventScript_BirdSculpture" to
            LilycoveCity_LilycoveMuseum_EventScript_BirdSculpture,
        "LilycoveCity_LilycoveMuseum_1F_EventScript_GrassPokemonPainting" to
            LilycoveCity_LilycoveMuseum_1F_EventScript_GrassPokemonPainting,
        "LilycoveCity_LilycoveMuseum_1F_EventScript_StoneTablet" to
            LilycoveCity_LilycoveMuseum_1F_EventScript_StoneTablet,
        "LilycoveCity_LilycoveMuseum_1F_EventScript_LegendaryPokemonPainting" to
            LilycoveCity_LilycoveMuseum_1F_EventScript_LegendaryPokemonPainting,
        "LilycoveCity_LilycoveMuseum_1F_EventScript_PokeBallSculpture" to
            LilycoveCity_LilycoveMuseum_1F_EventScript_PokeBallSculpture,
    )
