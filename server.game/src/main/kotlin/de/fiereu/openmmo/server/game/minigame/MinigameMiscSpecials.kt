package de.fiereu.openmmo.server.game.minigame

import de.fiereu.openmmo.server.game.script.ScriptContext
import de.fiereu.openmmo.server.game.script.vm.SpecialTable
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * r5 merge: the minigame-lane specials (SpecialOwners) the lane did not implement and the scripts
 * reach. Berry powder is Berry Crush's (records.berrycrush.powder); the powder vendor (Two Island /
 * Lilycove) spends it. The interview / easy chat / mixing / Wonder News ones have nothing more to
 * do here than their r5-minigames neighbours already do.
 */
@Singleton
class MinigameMiscSpecials @Inject constructor(private val save: MinigameSave) : SpecialTable() {
  private suspend fun powder(ctx: ScriptContext): Int {
    val c = ctx.state.characterId ?: return 0
    val r = save.section(c, "records")["berrycrush"] as? JsonObject
    return r?.get("powder")?.jsonPrimitive?.intOrNull ?: 0
  }

  private suspend fun setPowder(ctx: ScriptContext, v: Int) {
    val c = ctx.state.characterId ?: return
    val all = save.section(c, "records")
    val mine = all["berrycrush"] as? JsonObject ?: JsonObject(emptyMap())
    save.setSection(
        c,
        "records",
        JsonObject(all + ("berrycrush" to JsonObject(mine + ("powder" to JsonPrimitive(v))))))
  }

  init {
    // HasEnoughBerryPowder (berry_powder.c): VAR_0x8004 is the price.
    on("HasEnoughBerryPowder", "Script_HasEnoughBerryPowder") { ctx ->
      val ok = if (powder(ctx) >= ctx.specialVar(4)) 1 else 0
      ctx.result = ok
      ok
    }
    on("TakeBerryPowder", "Script_TakeBerryPowder") { ctx ->
      setPowder(ctx, (powder(ctx) - ctx.specialVar(4)).coerceAtLeast(0))
      0
    }
    on("DisplayBerryPowderVendorMenu", "PrintPlayerBerryPowderAmount") { ctx ->
      ctx.bufferString(1, powder(ctx).toString())
      0
    }
    noop(
        "RemoveBerryPowderVendorMenu",
        "ShowEasyChatMessage",
        "GabbyAndTyBeforeInterview",
        "GabbyAndTyAfterInterview",
        "SetContestCategoryStringVarForInterview")
    on("ShouldHideFanClubInterviewer") { ctx -> 0 }
    on("ValidateMixingGameLanguage") { ctx -> 0 }
    // No Wonder News (only Wonder Cards are distributed).
    on("WonderNews_GetRewardInfo") { ctx -> 0 }
  }
}
