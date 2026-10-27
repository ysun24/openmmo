package de.fiereu.openmmo.server.game.script.generated.hoenn

import de.fiereu.openmmo.dialog.generated.hoenn.PetalburgCity_PokemonCenter_1F
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/**
 * Not ported yet. Decomp body:
 * ```
 * setvar VAR_0x800B, LOCALID_PETALBURG_NURSE
 * call Common_EventScript_PkmnCenterNurse
 * waitmessage
 * waitbuttonpress
 * release
 * end
 * ```
 */
internal object PetalburgCity_PokemonCenter_1F_EventScript_Nurse : Script {
  override suspend fun run(ctx: ScriptContext) = healAtPokemonCenter(ctx)
}

internal object PetalburgCity_PokemonCenter_1F_EventScript_FatMan : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(PetalburgCity_PokemonCenter_1F.PCStorageSystem)
}

internal object PetalburgCity_PokemonCenter_1F_EventScript_Youngster : Script {
  override suspend fun run(ctx: ScriptContext) =
      ctx.say(PetalburgCity_PokemonCenter_1F.OranBerryRegainedHP)
}

internal val PetalburgCity_PokemonCenter_1FScripts: Map<String, Script> =
    mapOf(
        "PetalburgCity_PokemonCenter_1F_EventScript_Nurse" to
            PetalburgCity_PokemonCenter_1F_EventScript_Nurse,
        "PetalburgCity_PokemonCenter_1F_EventScript_FatMan" to
            PetalburgCity_PokemonCenter_1F_EventScript_FatMan,
        "PetalburgCity_PokemonCenter_1F_EventScript_Youngster" to
            PetalburgCity_PokemonCenter_1F_EventScript_Youngster,
    )
