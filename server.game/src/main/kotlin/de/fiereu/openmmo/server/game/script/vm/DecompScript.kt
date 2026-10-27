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

    private val CONTINUE_MODES =
        setOf(
            ScriptVm.MODE_CONTINUE_SCRIPT,
            ScriptVm.MODE_CONTINUE_SCRIPT_NO_MUSIC,
            ScriptVm.MODE_DOUBLE,
            ScriptVm.MODE_CONTINUE_SCRIPT_DOUBLE,
            ScriptVm.MODE_CONTINUE_SCRIPT_DOUBLE_NO_MUSIC,
            ScriptVm.MODE_REMATCH_DOUBLE,
        )

    /**
     * True when the trainer npc's script needs the interpreter rather than the field service's
     * plain intro / battle / defeat flow: it does not start with its trainerbattle, or that
     * continues into an event script, is a double battle, or (FRLG) the early rival battle.
     */
    fun interpretsTrainer(region: String, label: String): Boolean {
      val img = imageFor(region, label) ?: return false
      val a = img.labels[label] ?: return false
      // A script that does more before its battle (Mt Moon's Miguel) is the interpreter's.
      if (img.commands.getOrNull(img.u8(a)) != "trainerbattle") return true
      val mode = img.u8(a + 1)
      return mode in CONTINUE_MODES ||
          (img.region == "kanto" && mode == ScriptVm.MODE_EARLY_RIVAL_OR_PYRAMID)
    }

    /** A trainer seeing the player continues on the interpreter (its trainerbattle goes on). */
    fun approachInterprets(region: String, label: String): Boolean {
      val img = imageFor(region, label) ?: return false
      val a = img.labels[label] ?: return false
      return img.commands.getOrNull(img.u8(a)) == "trainerbattle" && interpretsTrainer(region, label)
    }

    /**
     * A trainer that saw the player and walked up (the field service did the approach): the
     * decomp's EventScript_StartTrainerApproach from the intro speech on, configured from the
     * trainer's own script.
     */
    suspend fun runTrainerApproach(ctx: ScriptContext, label: String, entityIdx: Int) {
      val localId = entityIdx
      val img = imageFor(ctx.regionName, label) ?: return
      val a = img.labels[label] ?: return
      val vm = ScriptVm(ctx, img)
      vm.lastTalked = localId + 1
      vm.configureTrainerBattle(a + 1, approaching = true)
      vm.runLabel("EventScript_ShowTrainerIntroMsg")
    }

    /** gSpecialVar_LastTalked: the decomp local id (1-based) of the npc talked to, or 0. */
    fun localIdOf(ctx: ScriptContext): Int {
      if (ctx.entityId < 0) return 0
      val map = ctx.vmCurrentMap() ?: return 0
      for (npc in map.npcs) {
        if (ctx.movement.npcEntityId(ctx.state, npc.entityIdx) == ctx.entityId) return npc.entityIdx + 1
      }
      return 0
    }
  }
}
