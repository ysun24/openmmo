package de.fiereu.openmmo.server.game.script

import de.fiereu.openmmo.server.game.script.generated.GeneratedScripts
import de.fiereu.openmmo.server.game.script.vm.DecompScript

/**
 * Finds the [Script] for a decomp script label, or null when nothing is wired for it. A hand port
 * wins; every other label of either game's event scripts runs on the decomp script interpreter.
 */
class ScriptRegistry(
    private val byLabel: Map<String, Script>,
    private val interpret: Boolean = true,
) {
  fun forLabel(scriptLabel: String): Script? =
      byLabel[scriptLabel] ?: interpreted(scriptLabel)

  /** The hand port of [scriptLabel] only, without falling back to the interpreter. */
  fun handPorted(scriptLabel: String): Script? = byLabel[scriptLabel]

  private val cache = java.util.concurrent.ConcurrentHashMap<String, Script>()

  private fun interpreted(label: String): Script? {
    if (!interpret || !DecompScript.exists(label)) return null
    return cache.getOrPut(label) { DecompScript(label) }
  }

  companion object {
    fun generated(): ScriptRegistry = ScriptRegistry(GeneratedScripts.byLabel)
  }
}
