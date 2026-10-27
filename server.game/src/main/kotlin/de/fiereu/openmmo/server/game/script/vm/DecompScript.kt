package de.fiereu.openmmo.server.game.script.vm

import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext

/**
 * A decomp script label run by the interpreter: the fallback for every label without a hand port.
 * The label is looked up in the image of the region the player is in, then the other one (both
 * games' std scripts share names).
 */
class DecompScript(val label: String) : Script {
  override suspend fun run(ctx: ScriptContext) {
    val img = imageFor(ctx.regionName, label) ?: return
    val vm = ScriptVm(ctx, img)
    vm.lastTalked = localIdOf(ctx)
    vm.runLabel(label)
  }

  companion object {
    fun imageFor(region: String, label: String): ScriptImage? {
      val own = ScriptImage.of(region)
      if (own?.labels?.containsKey(label) == true) return own
      val other = ScriptImage.of(if (region == "hoenn") "kanto" else "hoenn")
      return other?.takeIf { it.labels.containsKey(label) }
    }

    fun exists(label: String): Boolean =
        label.isNotEmpty() &&
            (ScriptImage.of("kanto")?.labels?.containsKey(label) == true ||
                ScriptImage.of("hoenn")?.labels?.containsKey(label) == true)

    /** gSpecialVar_LastTalked: the local id of the npc the player talked to, or 0. */
    fun localIdOf(ctx: ScriptContext): Int {
      if (ctx.entityId < 0) return 0
      val map = ctx.vmCurrentMap() ?: return 0
      for (npc in map.npcs) {
        if (ctx.movement.npcEntityId(ctx.state, npc.entityIdx) == ctx.entityId) return npc.entityIdx
      }
      return 0
    }
  }
}
